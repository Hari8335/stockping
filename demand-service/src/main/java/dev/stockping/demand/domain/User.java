package dev.stockping.demand.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {
    @Id
    private UUID id;
    @Column(nullable = false)
    private UUID merchantId;
    @Column(nullable = false, length = 254)
    private String email;
    @Column(nullable = false, length = 100)
    private String passwordHash;
    @Column(nullable = false, length = 32)
    private String role;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private AccountStatus status;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected User() {}
    public User(UUID merchantId, String email, String passwordHash) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.email = email.trim().toLowerCase(java.util.Locale.ROOT);
        this.passwordHash = passwordHash;
        this.role = "MERCHANT_ADMIN";
        this.status = AccountStatus.ACTIVE;
        this.createdAt = this.updatedAt = Instant.now();
    }
    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getRole() { return role; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
