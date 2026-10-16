package dev.stockping.catalog.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name = "stock_events")
public class StockEvent {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID merchantId;
    @Column(nullable = false, updatable = false) private UUID variantId;
    @Column(nullable = false, updatable = false) private int previousQuantity;
    @Column(nullable = false, updatable = false) private int newQuantity;
    @Column(nullable = false, updatable = false, length = 32) private String source;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    protected StockEvent() {}
    StockEvent(UUID merchantId, UUID variantId, int previousQuantity, int newQuantity) {
        Availability.fromQuantity(previousQuantity); Availability.fromQuantity(newQuantity);
        if (previousQuantity == newQuantity) throw new IllegalArgumentException("Quantity must change");
        this.id = UUID.randomUUID(); this.merchantId = merchantId; this.variantId = variantId;
        this.previousQuantity = previousQuantity; this.newQuantity = newQuantity;
        this.source = "MANUAL"; this.createdAt = Instant.now();
    }
    public boolean isRestockTransition() { return previousQuantity == 0 && newQuantity > 0; }
    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getVariantId() { return variantId; }
    public int getPreviousQuantity() { return previousQuantity; }
    public int getNewQuantity() { return newQuantity; }
    public String getSource() { return source; }
    public Instant getCreatedAt() { return createdAt; }
}
