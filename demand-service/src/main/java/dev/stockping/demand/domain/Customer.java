package dev.stockping.demand.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer {
    @Id
    private UUID id;
    @Column(nullable = false)
    private UUID merchantId;
    @Column(nullable = false, length = 16)
    private String whatsappPhone;
    @Column(length = 120)
    private String displayName;
    @Column(length = 16)
    private String language;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private AccountStatus status;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected Customer() {}
    public Customer(UUID merchantId, String whatsappPhone, String displayName, String language) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.whatsappPhone = whatsappPhone;
        this.displayName = displayName;
        this.language = language;
        this.status = AccountStatus.ACTIVE;
        this.createdAt = this.updatedAt = Instant.now();
    }
    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getWhatsappPhone() { return whatsappPhone; }
    public String getDisplayName() { return displayName; }
    public String getLanguage() { return language; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
