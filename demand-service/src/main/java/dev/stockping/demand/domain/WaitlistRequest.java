package dev.stockping.demand.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "waitlist_requests")
public class WaitlistRequest {
    @Id
    private UUID id;
    @Column(nullable = false)
    private UUID merchantId;
    @Column(nullable = false)
    private UUID customerId;
    @Column(nullable = false)
    private UUID variantId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private WaitlistStatus status;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    @Column
    private Instant notifiedAt;

    protected WaitlistRequest() {}
    public WaitlistRequest(UUID merchantId, UUID customerId, UUID variantId) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.customerId = customerId;
        this.variantId = variantId;
        this.status = WaitlistStatus.WAITING;
        this.createdAt = this.updatedAt = Instant.now();
    }
    public void optOut() {
        if (status.isActive()) {
            status = WaitlistStatus.OPTED_OUT;
            updatedAt = Instant.now();
        }
    }
    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getCustomerId() { return customerId; }
    public UUID getVariantId() { return variantId; }
    public WaitlistStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getNotifiedAt() { return notifiedAt; }
}
