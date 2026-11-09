package dev.stockping.demand.messaging;

import dev.stockping.contracts.demand.v1.*;
import dev.stockping.contracts.events.*;
import dev.stockping.demand.grpc.MessageCatalogClient;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Service-owned SQL uses the same transaction manager/customer locks as consent and waitlist JPA. */
@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MessageCatalogClient catalog;
    public NotificationService(JdbcTemplate jdbc, ObjectMapper json, MessageCatalogClient catalog) {
        this.jdbc = jdbc; this.json = json; this.catalog = catalog;
    }

    @Transactional(timeout = 15)
    public void process(StockAvailable event) {
        event.validate();
        UUID merchant = UUID.fromString(event.merchantId()), variant = UUID.fromString(event.variantId()), eventId = UUID.fromString(event.eventId());
        int inserted = jdbc.update("INSERT INTO availability_events(id,merchant_id,variant_id,payload,outcome) VALUES(?,?,?,?::jsonb,'PROCESSED') ON CONFLICT DO NOTHING",
                eventId, merchant, variant, json.writeValueAsString(event));
        if (inserted == 0) return;
        var stock = catalog.notificationStock(merchant, variant, event.correlationId());
        if (!activeMerchant(merchant) || stock.isEmpty() || !stock.get().productId().equals(event.productId()) || !stock.get().available()) {
            jdbc.update("UPDATE availability_events SET outcome='SKIPPED' WHERE id=?", eventId); return;
        }
        var waiters = jdbc.queryForList("SELECT id,customer_id FROM waitlist_requests WHERE merchant_id=? AND variant_id=? AND status='WAITING' AND created_at<=? ORDER BY customer_id,id",
                merchant, variant, Timestamp.from(Instant.parse(event.occurredAt())));
        for (var waiter : waiters) {
            UUID customer = (UUID) waiter.get("customer_id"), waitlist = (UUID) waiter.get("id");
            lockCustomer(merchant, customer);
            var rows = jdbc.queryForList("SELECT status FROM waitlist_requests WHERE id=? AND merchant_id=? FOR UPDATE", waitlist, merchant);
            if (rows.isEmpty() || !"WAITING".equals(rows.getFirst().get("status")) || !consented(merchant, customer)) continue;
            UUID id = UUID.randomUUID(), correlation = UUID.fromString(event.correlationId());
            jdbc.update("INSERT INTO notification_jobs(id,availability_event_id,merchant_id,variant_id,customer_id,waitlist_id,correlation_id,status) VALUES(?,?,?,?,?,?,?,'QUEUED')",
                    id, eventId, merchant, variant, customer, waitlist, correlation);
            jdbc.update("UPDATE waitlist_requests SET status='NOTIFICATION_QUEUED',updated_at=now() WHERE id=?", waitlist);
            var request = new NotificationRequested(id.toString(), NotificationRequested.TYPE, 1, Instant.now().toString(),
                    merchant.toString(), id.toString(), event.eventId(), event.correlationId());
            jdbc.update("INSERT INTO event_outbox(id,routing_key,payload) VALUES(?,?,?::jsonb)", id, NotificationRequested.TYPE, json.writeValueAsString(request));
        }
    }

    @Transactional(timeout = 10)
    public AuthorizeNotificationResponse authorize(AuthorizeNotificationRequest request) {
        UUID merchant = StockAvailable.uuid(request.getMerchantId()), id = StockAvailable.uuid(request.getNotificationId());
        UUID event = StockAvailable.uuid(request.getAvailabilityEventId()), correlation = StockAvailable.uuid(request.getCorrelationId());
        var jobs = jdbc.queryForList("SELECT * FROM notification_jobs WHERE id=? AND merchant_id=? AND availability_event_id=? AND correlation_id=?", id, merchant, event, correlation);
        if (jobs.isEmpty()) return denied("job_not_found");
        var job = jobs.getFirst();
        UUID customer = (UUID) job.get("customer_id"), waitlist = (UUID) job.get("waitlist_id");
        lockCustomer(merchant, customer);
        job = jdbc.queryForMap("SELECT * FROM notification_jobs WHERE id=? FOR UPDATE", id);
        if (!Set.of("QUEUED", "AUTHORIZED").contains(job.get("status"))) return denied("job_terminal");
        String reason = null;
        if (!activeMerchant(merchant) || !consented(merchant, customer)) reason = "consent_or_account";
        String waitlistStatus = jdbc.queryForObject("SELECT status FROM waitlist_requests WHERE id=? AND merchant_id=? AND customer_id=? AND variant_id=? FOR UPDATE",
                String.class, waitlist, merchant, customer, job.get("variant_id"));
        if (!"NOTIFICATION_QUEUED".equals(waitlistStatus)) reason = "waitlist_ineligible";
        MessageCatalogClient.NotificationStock stock = null;
        if (reason == null) {
            stock = catalog.notificationStock(merchant, (UUID) job.get("variant_id"), correlation.toString()).orElse(null);
            if (stock == null || !stock.available()) reason = "stock_unavailable";
        }
        if (reason != null) {
            finish(job, "SKIPPED", reason, "");
            return denied(reason);
        }
        jdbc.update("UPDATE notification_jobs SET status='AUTHORIZED',authorized_at=now() WHERE id=?", id);
        String phone = jdbc.queryForObject("SELECT whatsapp_phone FROM customers WHERE id=? AND merchant_id=?", String.class, customer, merchant);
        return AuthorizeNotificationResponse.newBuilder().setEligible(true).setCustomerPhone(phone)
                .setProductName(stock.name()).setSku(stock.sku()).setVariantDescription(stock.description()).build();
    }

    @Transactional(timeout = 10)
    public void complete(CompleteNotificationRequest request) {
        UUID merchant = StockAvailable.uuid(request.getMerchantId()), id = StockAvailable.uuid(request.getNotificationId());
        if (!Set.of("ACCEPTED", "FAILED", "UNKNOWN", "SKIPPED").contains(request.getStatus())
                || !request.getReason().matches("[a-z_]{0,64}") || request.getProviderMessageId().length() > 512
                || (request.getStatus().equals("ACCEPTED") && request.getProviderMessageId().isBlank())) throw new IllegalArgumentException();
        var rows = jdbc.queryForList("SELECT * FROM notification_jobs WHERE id=? AND merchant_id=?", id, merchant);
        if (rows.isEmpty()) throw new IllegalArgumentException();
        lockCustomer(merchant, (UUID) rows.getFirst().get("customer_id"));
        var job = jdbc.queryForMap("SELECT * FROM notification_jobs WHERE id=? FOR UPDATE", id);
        if (!Set.of("QUEUED", "AUTHORIZED").contains(job.get("status"))) return; // Idempotent outcome replay.
        finish(job, request.getStatus(), request.getReason(), request.getProviderMessageId());
    }

    private void finish(Map<String, Object> job, String status, String reason, String provider) {
        jdbc.update("UPDATE notification_jobs SET status=?,reason=NULLIF(?,''),provider_message_id=NULLIF(?,''),completed_at=now() WHERE id=?",
                status, reason, provider, job.get("id"));
        String waitlistStatus = status.equals("ACCEPTED") ? "NOTIFIED" : reason.equals("stock_unavailable") ? "WAITING" : "CANCELLED";
        // Never undo STOP. Historical consent changes and their waitlist states remain authoritative.
        jdbc.update("UPDATE waitlist_requests SET status=?,updated_at=now(),notified_at=CASE WHEN ?='NOTIFIED' THEN now() ELSE notified_at END WHERE id=? AND status='NOTIFICATION_QUEUED'",
                waitlistStatus, waitlistStatus, job.get("waitlist_id"));
    }
    private void lockCustomer(UUID merchant, UUID customer) {
        jdbc.queryForList("SELECT id FROM customers WHERE merchant_id=? AND id=? FOR UPDATE", merchant, customer);
    }
    private boolean activeMerchant(UUID merchant) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM merchants WHERE id=? AND status='ACTIVE')", Boolean.class, merchant));
    }
    private boolean consented(UUID merchant, UUID customer) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM customers c WHERE c.id=? AND c.merchant_id=? AND c.status='ACTIVE'
                AND (SELECT status FROM consent_records WHERE merchant_id=c.merchant_id AND customer_id=c.id ORDER BY sequence DESC LIMIT 1)='OPT_IN')
                """, Boolean.class, customer, merchant));
    }
    private AuthorizeNotificationResponse denied(String reason) { return AuthorizeNotificationResponse.newBuilder().setReason(reason).build(); }
}
