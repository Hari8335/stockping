package dev.stockping.demand.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchants")
public class Merchant {
    @Id
    private UUID id;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(nullable = false, length = 80)
    private String timezone;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private AccountStatus status;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected Merchant() {}
    public Merchant(UUID id, String name, String timezone) {
        this.id = id;
        this.name = name;
        this.timezone = java.time.ZoneId.of(timezone).getId();
        this.status = AccountStatus.ACTIVE;
        this.createdAt = this.updatedAt = Instant.now();
    }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getTimezone() { return timezone; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
