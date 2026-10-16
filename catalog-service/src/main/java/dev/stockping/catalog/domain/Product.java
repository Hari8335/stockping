package dev.stockping.catalog.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name = "products")
public class Product {
    @Id private UUID id;
    @Column(nullable = false) private UUID merchantId;
    @Column(nullable = false, length = 200) private String name;
    @Column(nullable = false, length = 100) private String brand;
    @Column(nullable = false, length = 100) private String category;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private CatalogStatus status;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    protected Product() {}
    public Product(UUID merchantId, String name, String brand, String category, CatalogStatus status) {
        this.id = UUID.randomUUID(); this.merchantId = merchantId;
        this.createdAt = Instant.now(); update(name, brand, category, status);
    }
    public void update(String name, String brand, String category, CatalogStatus status) {
        if (name != null) this.name = name.trim();
        if (brand != null) this.brand = brand.trim();
        if (category != null) this.category = category.trim();
        if (status != null) this.status = status;
        updatedAt = Instant.now();
    }
    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getName() { return name; }
    public String getBrand() { return brand; }
    public String getCategory() { return category; }
    public CatalogStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
