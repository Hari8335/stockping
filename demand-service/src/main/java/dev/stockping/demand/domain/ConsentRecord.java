package dev.stockping.demand.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "consent_records")
public class ConsentRecord {
    @Id
    private UUID id;
    @Column(insertable = false, updatable = false)
    private Long sequence;
    @Column(nullable = false)
    private UUID merchantId;
    @Column(nullable = false)
    private UUID customerId;
    @Column(nullable = false, length = 32)
    private String type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private ConsentStatus status;
    @Column(nullable = false, length = 32)
    private String source;
    @Column(nullable = false)
    private Instant capturedAt;

    protected ConsentRecord() {}
    public ConsentRecord(UUID merchantId, UUID customerId, ConsentStatus status) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.customerId = customerId;
        this.type = "RESTOCK_NOTIFICATIONS";
        this.status = status;
        this.source = "MANUAL_API";
        this.capturedAt = Instant.now();
    }
    public UUID getId() { return id; }
    public Long getSequence() { return sequence; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getCustomerId() { return customerId; }
    public String getType() { return type; }
    public ConsentStatus getStatus() { return status; }
    public String getSource() { return source; }
    public Instant getCapturedAt() { return capturedAt; }
}
