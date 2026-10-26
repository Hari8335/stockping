package dev.stockping.demand.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "demand_audit")
public class DemandAudit {
    @Id
    private UUID id;
    @Column(nullable = false)
    private UUID merchantId;
    @Column(nullable = false, length = 40)
    private String eventType;
    @Column(nullable = false)
    private UUID entityId;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private java.util.Map<String, String> metadataJson;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected DemandAudit() {}
    public DemandAudit(UUID merchantId, String eventType, UUID entityId, java.util.Map<String, String> metadata) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.eventType = eventType;
        this.entityId = entityId;
        this.metadataJson = java.util.Map.copyOf(metadata);
        this.createdAt = Instant.now();
    }
    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getEventType() { return eventType; }
    public UUID getEntityId() { return entityId; }
    public java.util.Map<String, String> getMetadataJson() { return metadataJson; }
    public Instant getCreatedAt() { return createdAt; }
}
