package dev.stockping.catalog.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name = "inventory")
public class Inventory {
    @Id private UUID id;
    @Column(nullable = false) private UUID merchantId;
    @Column(nullable = false) private UUID variantId;
    @Column(nullable = false) private int quantity;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Availability availabilityStatus;
    @Column(nullable = false) private Instant updatedAt;
    protected Inventory() {}
    public Inventory(UUID merchantId, UUID variantId) {
        this.id = UUID.randomUUID(); this.merchantId = merchantId; this.variantId = variantId;
        this.availabilityStatus = Availability.UNAVAILABLE; this.updatedAt = Instant.now();
    }
    // The caller persists the returned event in the same transaction.
    public java.util.Optional<StockEvent> setQuantity(int quantity) {
        Availability availability = Availability.fromQuantity(quantity);
        if (this.quantity == quantity) return java.util.Optional.empty();
        var event = new StockEvent(merchantId, variantId, this.quantity, quantity);
        this.quantity = quantity; this.availabilityStatus = availability; this.updatedAt = event.getCreatedAt();
        return java.util.Optional.of(event);
    }
    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getVariantId() { return variantId; }
    public int getQuantity() { return quantity; }
    public Availability getAvailabilityStatus() { return availabilityStatus; }
    public Instant getUpdatedAt() { return updatedAt; }
}
