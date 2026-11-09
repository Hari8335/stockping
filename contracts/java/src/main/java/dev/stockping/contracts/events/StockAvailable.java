package dev.stockping.contracts.events;

import java.time.Instant;
import java.util.UUID;

/** JSON wire contract; contains no customer information or persistence dependencies. */
public record StockAvailable(String eventId, String eventType, Integer eventVersion, String occurredAt,
        String merchantId, String productId, String variantId, String sku,
        Integer previousQuantity, Integer newQuantity, String correlationId) {
    public static final String TYPE = "inventory.stock_available.v1";
    public void validate() {
        uuid(eventId); uuid(merchantId); uuid(productId); uuid(variantId); uuid(correlationId);
        if (!TYPE.equals(eventType) || eventVersion == null || previousQuantity == null || newQuantity == null
                || eventVersion != 1 || previousQuantity != 0 || newQuantity <= 0
                || sku == null || sku.isBlank() || sku.length() > 100
                || occurredAt == null || Instant.parse(occurredAt).isAfter(Instant.now().plusSeconds(300)))
            throw new IllegalArgumentException("Invalid stock availability event");
    }
    public static UUID uuid(String value) {
        UUID id = UUID.fromString(value == null ? "" : value);
        if (!id.toString().equals(value) || id.equals(new UUID(0, 0))) throw new IllegalArgumentException("Invalid UUID");
        return id;
    }
}
