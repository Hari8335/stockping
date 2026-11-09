package dev.stockping.demand.messaging;

import dev.stockping.demand.ai.*;
import dev.stockping.demand.domain.*;
import dev.stockping.demand.exception.DemandException;
import dev.stockping.demand.grpc.MessageCatalogClient;
import dev.stockping.demand.repository.*;
import dev.stockping.demand.service.ConsentService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles incoming customer messages from WhatsApp.
 * Supports deterministic commands (HELP, STOP, STOCK, WAITLIST) and
 * natural-language stock queries via constrained AI extraction.
 */
@Service
public class CustomerMessageService {

    private static final Logger LOG = LoggerFactory.getLogger(CustomerMessageService.class);

    public record Message(UUID merchant, String phone, String displayName, String providerId,
                          String text, Instant receivedAt, String correlationId) {}

    public record Reply(boolean sendText, String text, String purpose) {}

    public record QueryMemory(String sku, UUID variantId, String productName, Instant timestamp) {}

    private static final String HELP = "StockPing can check product stock and manage restock alerts. Commands:\n"
            + "- Send STOCK <SKU> to check an exact item.\n"
            + "- Ask in natural language (e.g. 'Is S25 Ultra 512GB Black available?').\n"
            + "- Send WAITLIST <SKU> or reply YES to join a restock waitlist.\n"
            + "- Send STOP to stop restock notifications.";

    private final MerchantRepository merchants;
    private final CustomerRepository customers;
    private final ConsentService consent;
    private final MessageCatalogClient catalog;
    private final WaitlistRepository waitlists;
    private final DemandAuditRepository audits;
    private final AiExtractor aiExtractor;
    private final AiProperties aiProperties;

    // Ephemeral bounded in-memory cache to support "reply YES to join waitlist" flow (Scenario A)
    private final Map<String, QueryMemory> recentQueries = new ConcurrentHashMap<>();

    public CustomerMessageService(MerchantRepository merchants, CustomerRepository customers,
                                  ConsentService consent, MessageCatalogClient catalog) {
        this(merchants, customers, consent, catalog, null, null, null, new AiProperties());
    }

    @Autowired
    public CustomerMessageService(MerchantRepository merchants, CustomerRepository customers,
                                  ConsentService consent, MessageCatalogClient catalog,
                                  WaitlistRepository waitlists, DemandAuditRepository audits,
                                  AiExtractor aiExtractor, AiProperties aiProperties) {
        this.merchants = merchants;
        this.customers = customers;
        this.consent = consent;
        this.catalog = catalog;
        this.waitlists = waitlists;
        this.audits = audits;
        this.aiExtractor = aiExtractor;
        this.aiProperties = aiProperties != null ? aiProperties : new AiProperties();
    }

    @Transactional
    public Reply process(Message message) {
        if (merchants.findById(message.merchant()).filter(m -> m.getStatus() == AccountStatus.ACTIVE).isEmpty()) {
            throw new DemandException(404, "MERCHANT_NOT_FOUND", "Merchant unavailable");
        }

        String text = message.text().strip();
        String upper = text.toUpperCase(Locale.ROOT);

        // 1. Explicit deterministic Stage 3 commands
        if (upper.equals("STOP") || upper.equals("STOP NOTIFICATIONS")) {
            recordOptOut(message.merchant(), message.phone());
            return new Reply(true, "You will not receive restock notifications from this merchant.", "OPT_OUT");
        }
        if (upper.equals("HELP")) {
            return new Reply(true, HELP, "HELP");
        }
        if (upper.startsWith("STOCK ")) {
            String sku = text.substring(6).strip();
            if (sku.matches("[^\\s\\p{Cntrl}]{1,100}")) {
                return checkStockBySku(message.merchant(), message.phone(), sku, message.correlationId());
            }
        }

        // 2. Explicit waitlist opt-in commands
        if (upper.startsWith("WAITLIST ")) {
            String sku = text.substring(9).strip();
            if (sku.matches("[^\\s\\p{Cntrl}]{1,100}")) {
                return joinWaitlistBySku(message.merchant(), message.phone(), message.displayName(), sku, message.correlationId());
            }
        }
        if (upper.equals("YES") || upper.equals("CONFIRM") || upper.equals("OPT IN") || upper.equals("WAITLIST")) {
            String key = memoryKey(message.merchant(), message.phone());
            QueryMemory memory = recentQueries.get(key);
            if (memory != null && memory.timestamp().isAfter(Instant.now().minusSeconds(900))) {
                return joinWaitlistBySku(message.merchant(), message.phone(), message.displayName(), memory.sku(), message.correlationId());
            }
            return new Reply(true, "To join the restock waitlist, reply with: WAITLIST <SKU> or ask for a product first.", "WAITLIST_GUIDANCE");
        }

        // 3. Constrained natural-language extraction (Stage 5 AI)
        if (aiExtractor != null && (aiProperties.isEnabled() || "mock".equalsIgnoreCase(aiProperties.getProvider()))) {
            try {
                ProductExtractionResult extraction = aiExtractor.extract(text, message.correlationId());
                Reply aiReply = handleAiExtraction(message, extraction);
                if (aiReply != null) {
                    return aiReply;
                }
            } catch (Exception e) {
                LOG.warn("AI processing encountered an error correlationId={} err={}", message.correlationId(), e.getMessage());
            }
        }

        return new Reply(true, HELP, "FALLBACK");
    }

    private Reply checkStockBySku(UUID merchant, String phone, String sku, String correlation) {
        try {
            var stock = catalog.lookup(merchant, sku, correlation);
            if (stock.isEmpty()) {
                return new Reply(true, "That exact SKU was not found. Check the SKU and try again.", "STOCK");
            }
            var item = stock.get();
            rememberQuery(merchant, phone, sku, null, item.name());
            return new Reply(true, item.name() + " (" + sku + ") is currently "
                    + (item.available() ? "available." : "out of stock."), "STOCK");
        } catch (DemandException e) {
            return new Reply(true, "I cannot verify stock right now. Please try again later.", "STOCK_UNAVAILABLE");
        }
    }

    private Reply joinWaitlistBySku(UUID merchant, String phone, String displayName, String sku, String correlation) {
        if (waitlists == null) {
            return new Reply(true, "Waitlist joining is currently unavailable.", "WAITLIST_UNAVAILABLE");
        }
        try {
            var outcome = catalog.search(merchant, null, null, sku, Map.of(), correlation);
            if (outcome.candidates().isEmpty()) {
                return new Reply(true, "That SKU was not found in our catalog. Check the SKU and try again.", "WAITLIST_NOT_FOUND");
            }
            var candidate = outcome.candidates().get(0);
            var inventory = catalog.getInventory(merchant, candidate.variantId(), correlation);
            if (inventory.available()) {
                return new Reply(true, candidate.productName() + " (" + sku + ") is currently available in stock. No waitlist is needed.", "STOCK_AVAILABLE");
            }

            Customer customer = customers.findByMerchantIdAndWhatsappPhone(merchant, phone)
                    .orElseGet(() -> customers.saveAndFlush(new Customer(merchant, phone, displayName, "en")));
            consent.changeForMerchant(merchant, customer.getId(), ConsentStatus.OPT_IN, "CUSTOMER_MESSAGE");

            if (!waitlists.existsByMerchantIdAndCustomerIdAndVariantIdAndStatusIn(merchant, customer.getId(), candidate.variantId(), WaitlistStatus.ACTIVE)) {
                var waitlist = waitlists.saveAndFlush(new WaitlistRequest(merchant, customer.getId(), candidate.variantId()));
                if (audits != null) {
                    audits.save(new DemandAudit(merchant, "WAITLIST_CREATED", waitlist.getId(),
                            Map.of("variantId", candidate.variantId().toString(), "source", "CUSTOMER_MESSAGE")));
                }
            }
            return new Reply(true, "You've been added to the waitlist for " + candidate.productName() + " (" + sku
                    + "). We will notify you via WhatsApp as soon as stock arrives! Send STOP to cancel.", "WAITLIST_CONFIRMED");
        } catch (DemandException e) {
            return new Reply(true, "I cannot verify the waitlist right now. Please try again later.", "WAITLIST_UNAVAILABLE");
        }
    }

    private Reply handleAiExtraction(Message message, ProductExtractionResult extraction) {
        if (extraction.intent() == AiIntent.OPT_OUT && extraction.confidence() >= aiProperties.getMinConfidence()) {
            recordOptOut(message.merchant(), message.phone());
            return new Reply(true, "You will not receive restock notifications from this merchant.", "OPT_OUT");
        }

        if (extraction.intent() == AiIntent.HELP) {
            return new Reply(true, HELP, "HELP");
        }

        if (extraction.needsClarification() || extraction.confidence() < aiProperties.getMinConfidence()
                || extraction.intent() == AiIntent.UNKNOWN) {
            String prompt = (extraction.clarificationPrompt() != null && !extraction.clarificationPrompt().isBlank())
                    ? extraction.clarificationPrompt()
                    : "I could not identify the product you are asking about. " + HELP;
            return new Reply(true, prompt, "CLARIFICATION");
        }

        if (extraction.intent() == AiIntent.CHECK_STOCK || extraction.intent() == AiIntent.JOIN_WAITLIST) {
            return resolveCatalogAndRespond(message, extraction);
        }

        return null;
    }

    private Reply resolveCatalogAndRespond(Message message, ProductExtractionResult extraction) {
        try {
            String nameToSearch = (extraction.model() != null && !extraction.model().isBlank())
                    ? extraction.model() : extraction.productPhrase();

            // 1. First attempt: search with product name/model, brand, and extracted attributes
            var search = catalog.search(message.merchant(), nameToSearch, extraction.brand(), null, extraction.attributes(), message.correlationId());

            // 2. If no candidates, try searching productPhrase if different
            if (search.candidates().isEmpty() && extraction.productPhrase() != null && !extraction.productPhrase().isBlank()
                    && !extraction.productPhrase().equalsIgnoreCase(nameToSearch)) {
                search = catalog.search(message.merchant(), extraction.productPhrase(), extraction.brand(), null, extraction.attributes(), message.correlationId());
            }

            // 3. If still no candidates, try without attributes (in case attributes slightly differed)
            if (search.candidates().isEmpty() && !extraction.attributes().isEmpty()) {
                search = catalog.search(message.merchant(), nameToSearch, extraction.brand(), null, Map.of(), message.correlationId());
            }

            // Case A: NOT FOUND
            if (search.candidates().isEmpty()) {
                String searchLabel = !nameToSearch.isBlank() ? nameToSearch : "that product";
                return new Reply(true, "We couldn't find a matching product in our catalog for '" + searchLabel + "'. Check the SKU or model name and try again.", "STOCK_NOT_FOUND");
            }

            // Case B: AMBIGUOUS (> 1 candidate)
            if (search.candidates().size() > 1) {
                String options = search.candidates().stream().limit(3)
                        .map(c -> c.productName() + " (" + c.sku() + (c.attributes().isEmpty() ? "" : ", " + formatAttrs(c.attributes())) + ")")
                        .collect(Collectors.joining("; "));
                return new Reply(true, "We found multiple options: " + options + ". Please specify the exact model, storage, or color, or send STOCK <SKU>.", "STOCK_AMBIGUOUS");
            }

            // Case C: MATCHED (exactly 1 candidate)
            var candidate = search.candidates().get(0);
            var inventory = catalog.getInventory(message.merchant(), candidate.variantId(), message.correlationId());

            if (inventory.available()) {
                return new Reply(true, candidate.productName() + " (" + candidate.sku() + ") is currently available.", "STOCK_AVAILABLE");
            } else {
                rememberQuery(message.merchant(), message.phone(), candidate.sku(), candidate.variantId(), candidate.productName());
                if (extraction.intent() == AiIntent.JOIN_WAITLIST) {
                    return joinWaitlistBySku(message.merchant(), message.phone(), message.displayName(), candidate.sku(), message.correlationId());
                }
                return new Reply(true, candidate.productName() + " (" + candidate.sku() + ") is currently out of stock. Would you like to be notified when it arrives? Reply YES or send WAITLIST " + candidate.sku() + " to join the waitlist.", "STOCK_WAITLIST_OFFER");
            }

        } catch (DemandException e) {
            return new Reply(true, "I cannot verify stock right now. Please try again later.", "STOCK_UNAVAILABLE");
        }
    }

    private void recordOptOut(UUID merchant, String phone) {
        customers.findByMerchantIdAndWhatsappPhone(merchant, phone).ifPresent(customer ->
                consent.changeForMerchant(merchant, customer.getId(), ConsentStatus.OPT_OUT, "CUSTOMER_MESSAGE"));
    }

    private void rememberQuery(UUID merchant, String phone, String sku, UUID variantId, String name) {
        String key = memoryKey(merchant, phone);
        recentQueries.put(key, new QueryMemory(sku, variantId, name, Instant.now()));
        // Periodically purge old entries if map grows
        if (recentQueries.size() > 1000) {
            Instant cutoff = Instant.now().minusSeconds(1800);
            recentQueries.entrySet().removeIf(e -> e.getValue().timestamp().isBefore(cutoff));
        }
    }

    private static String memoryKey(UUID merchant, String phone) {
        return merchant.toString() + ":" + phone;
    }

    private static String formatAttrs(Map<String, String> attrs) {
        return attrs.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).collect(Collectors.joining(", "));
    }
}
