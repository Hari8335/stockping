package dev.stockping.demand.messaging;

import dev.stockping.demand.domain.*;
import dev.stockping.demand.exception.DemandException;
import dev.stockping.demand.grpc.MessageCatalogClient;
import dev.stockping.demand.repository.*;
import dev.stockping.demand.service.ConsentService;
import java.util.*;
import org.springframework.stereotype.Service;

/** Deterministic Stage 3 commands only. No provider payloads or free-form extraction. */
@Service
public class CustomerMessageService {
    public record Message(UUID merchant, String phone, String displayName, String providerId,
                          String text, java.time.Instant receivedAt, String correlationId) {}
    public record Reply(boolean sendText, String text, String purpose) {}
    private static final String HELP = "StockPing can currently check an exact SKU. Send STOCK <SKU>. Send STOP to stop restock notifications, or HELP for these commands.";
    private final MerchantRepository merchants;
    private final CustomerRepository customers;
    private final ConsentService consent;
    private final MessageCatalogClient catalog;
    public CustomerMessageService(MerchantRepository merchants, CustomerRepository customers, ConsentService consent, MessageCatalogClient catalog) {
        this.merchants = merchants; this.customers = customers; this.consent = consent; this.catalog = catalog;
    }
    public Reply process(Message message) {
        if (merchants.findById(message.merchant()).filter(m -> m.getStatus() == AccountStatus.ACTIVE).isEmpty())
            throw new DemandException(404, "MERCHANT_NOT_FOUND", "Merchant unavailable");
        String text = message.text().strip();
        String command = text.toUpperCase(Locale.ROOT);
        if (command.equals("STOP") || command.equals("STOP NOTIFICATIONS")) {
            customers.findByMerchantIdAndWhatsappPhone(message.merchant(), message.phone()).ifPresent(customer ->
                    consent.changeForMerchant(message.merchant(), customer.getId(), ConsentStatus.OPT_OUT, "CUSTOMER_MESSAGE"));
            return new Reply(true, "You will not receive restock notifications from this merchant.", "OPT_OUT");
        }
        if (command.equals("HELP")) return new Reply(true, HELP, "HELP");
        if (command.startsWith("STOCK ")) {
            String sku = text.substring(6).strip();
            if (sku.matches("[^\\s\\p{Cntrl}]{1,100}")) {
                try {
                    var stock = catalog.lookup(message.merchant(), sku, message.correlationId());
                    if (stock.isEmpty()) return new Reply(true, "That exact SKU was not found. Check the SKU and try again.", "STOCK");
                    var item = stock.get();
                    return new Reply(true, item.name() + " (" + sku + ") is currently "
                            + (item.available() ? "available." : "out of stock."), "STOCK");
                } catch (DemandException e) {
                    return new Reply(true, "I cannot verify stock right now. Please try again later.", "STOCK_UNAVAILABLE");
                }
            }
        }
        return new Reply(true, HELP, "FALLBACK");
    }
}
