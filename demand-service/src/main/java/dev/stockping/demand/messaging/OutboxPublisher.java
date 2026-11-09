package dev.stockping.demand.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Each attempt holds a row lock through the bounded confirm. A crash can repeat publication, never lose the row. */
@Component
@ConditionalOnProperty(name = "stockping.events.enabled", havingValue = "true")
public class OutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transaction;
    public OutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.rabbit = rabbit; this.transaction = new TransactionTemplate(manager);
        transaction.setTimeout(10);
    }
    @Scheduled(fixedDelayString = "${stockping.events.poll-ms:1000}")
    public void publish() {
        for (int i = 0; i < 20; i++) {
            try { if (!Boolean.TRUE.equals(transaction.execute(status -> one()))) return; }
            catch (Exception e) { LOG.warn("Outbox transaction failed errorCode=outbox_database"); return; }
        }
    }
    private boolean one() {
        var rows = jdbc.queryForList("SELECT id,routing_key,payload::text,attempts FROM event_outbox WHERE status='PENDING' AND next_attempt_at<=now() ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED");
        if (rows.isEmpty()) return false;
        var row = rows.getFirst();
        UUID id = (UUID) row.get("id");
        int attempt = ((Number) row.get("attempts")).intValue() + 1;
        try {
            var properties = new MessageProperties();
            properties.setContentType("application/json");
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setMessageId(id.toString());
            var confirmation = new CorrelationData(id.toString());
            rabbit.send(EventConfiguration.EXCHANGE, (String) row.get("routing_key"),
                    new Message(((String) row.get("payload")).getBytes(StandardCharsets.UTF_8), properties), confirmation);
            if (!confirmation.getFuture().get(3, TimeUnit.SECONDS).ack() || confirmation.getReturned() != null)
                throw new IllegalStateException("publish_not_confirmed");
            jdbc.update("UPDATE event_outbox SET status='PUBLISHED',attempts=?,published_at=now(),error_code=NULL WHERE id=?", attempt, id);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            jdbc.update("UPDATE event_outbox SET status=?,attempts=?,next_attempt_at=now()+(? * interval '1 second'),error_code='broker_publish_failed' WHERE id=?",
                    attempt >= 20 ? "PARKED" : "PENDING", attempt, Math.min(300, 1 << Math.min(attempt, 8)), id);
            LOG.warn("Outbox publication deferred eventId={} attempt={} parked={}", id, attempt, attempt >= 20);
            return false;
        }
        return true;
    }
}
