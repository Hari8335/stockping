package dev.stockping.catalog.dto;

import dev.stockping.catalog.domain.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CatalogDtos {
    private CatalogDtos() {}
    public record CreateProduct(@NotBlank @Size(max=200) String name,
            @NotBlank @Size(max=100) String brand, @NotBlank @Size(max=100) String category,
            CatalogStatus status) {}
    // Null fields mean unchanged; blank strings are rejected. Status defaults ACTIVE on creation.
    public record UpdateProduct(@Pattern(regexp="(?s).*\\S.*") @Size(max=200) String name,
            @Pattern(regexp="(?s).*\\S.*") @Size(max=100) String brand,
            @Pattern(regexp="(?s).*\\S.*") @Size(max=100) String category, CatalogStatus status) {}
    public record CreateVariant(@NotBlank @Size(max=100) String sku,
            @NotNull @Size(max=20) Map<String, String> attributes,
            @DecimalMin("0.00") @Digits(integer=17, fraction=2) BigDecimal price, CatalogStatus status) {}
    public record UpdateVariant(@Pattern(regexp="(?s).*\\S.*") @Size(max=100) String sku,
            @Size(max=20) Map<String, String> attributes,
            @DecimalMin("0.00") @Digits(integer=17, fraction=2) BigDecimal price, CatalogStatus status) {}
    public record UpdateInventory(@NotNull @Min(0) Integer quantity) {}
    public record ProductView(UUID id, String name, String brand, String category, CatalogStatus status,
            Instant createdAt, Instant updatedAt) {
        public static ProductView from(Product p) { return new ProductView(p.getId(), p.getName(), p.getBrand(),
                p.getCategory(), p.getStatus(), p.getCreatedAt(), p.getUpdatedAt()); }
    }
    public record ProductDetail(ProductView product, List<VariantView> variants) {}
    public record VariantView(UUID id, UUID productId, String productName, String brand, String sku,
            Map<String,String> attributes, BigDecimal price, CatalogStatus status, Instant createdAt, Instant updatedAt) {
        public static VariantView from(ProductVariant v) { return new VariantView(v.getId(), v.getProduct().getId(),
                v.getProduct().getName(), v.getProduct().getBrand(), v.getSku(), v.getAttributes(), v.getPrice(),
                v.getStatus(), v.getCreatedAt(), v.getUpdatedAt()); }
    }
    public record InventoryView(UUID variantId, int quantity, Availability availabilityStatus, Instant updatedAt) {
        public static InventoryView from(Inventory i) { return new InventoryView(i.getVariantId(), i.getQuantity(),
                i.getAvailabilityStatus(), i.getUpdatedAt()); }
    }
    public record InventoryUpdate(InventoryView inventory, boolean changed, boolean restockTransition, UUID stockEventId) {}
    public record PageView<T>(List<T> items, long totalElements, int page, int size) {}
    public enum Resolution { MATCHED, NOT_FOUND, AMBIGUOUS }
    public record SearchResult(Resolution resolutionStatus, List<VariantView> candidates, long totalMatches, int page, int size) {}
}
