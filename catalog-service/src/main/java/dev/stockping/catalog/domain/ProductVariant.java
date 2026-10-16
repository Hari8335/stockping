package dev.stockping.catalog.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name = "product_variants")
public class ProductVariant {
    @Id private UUID id;
    @Column(nullable = false) private UUID merchantId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false) private Product product;
    @Column(nullable = false, length = 100) private String sku;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "attributes_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> attributes;
    @Column(precision = 19, scale = 2) private BigDecimal price;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private CatalogStatus status;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    protected ProductVariant() {}
    public ProductVariant(Product product, String sku, Map<String, String> attributes, BigDecimal price, CatalogStatus status) {
        this.id = UUID.randomUUID(); this.merchantId = product.getMerchantId(); this.product = product;
        this.createdAt = Instant.now(); update(sku, attributes, price, status);
    }
    public void update(String sku, Map<String, String> attributes, BigDecimal price, CatalogStatus status) {
        if (sku != null) this.sku = sku.trim();
        if (attributes != null) this.attributes = Map.copyOf(attributes);
        if (price != null) this.price = price;
        if (status != null) this.status = status;
        this.updatedAt = Instant.now();
    }
    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public Product getProduct() { return product; }
    public String getSku() { return sku; }
    public Map<String, String> getAttributes() { return Map.copyOf(attributes); }
    public BigDecimal getPrice() { return price; }
    public CatalogStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
