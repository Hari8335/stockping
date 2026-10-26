package dev.stockping.catalog.service;

import dev.stockping.catalog.config.MerchantContextProvider;
import dev.stockping.catalog.domain.*;
import dev.stockping.catalog.dto.CatalogDtos.*;
import dev.stockping.catalog.exception.*;
import dev.stockping.catalog.repository.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service @Validated @Transactional(readOnly = true)
public class CatalogService {
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final InventoryRepository inventories;
    private final StockEventRepository events;
    private final MerchantContextProvider merchants;
    public CatalogService(ProductRepository products, ProductVariantRepository variants, InventoryRepository inventories,
            StockEventRepository events, MerchantContextProvider merchants) {
        this.products = products; this.variants = variants; this.inventories = inventories;
        this.events = events; this.merchants = merchants;
    }
    @Transactional
    public ProductView createProduct(@NotNull @Valid CreateProduct request) {
        return ProductView.from(products.save(new Product(merchants.currentMerchantId(), request.name(), request.brand(),
                request.category(), request.status() == null ? CatalogStatus.ACTIVE : request.status())));
    }
    public PageView<ProductView> listProducts(String name, String brand, CatalogStatus status, int page, int size) {
        validateText(name, 200); validateText(brand, 100); validatePage(page, size);
        UUID merchant = merchants.currentMerchantId();
        Specification<Product> spec = (r,q,b) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(b.equal(r.get("merchantId"), merchant));
            if (name != null) predicates.add(b.like(b.lower(r.get("name")), "%" + escape(name.trim().toLowerCase(Locale.ROOT)) + "%", '\\'));
            if (brand != null) predicates.add(b.equal(b.lower(r.get("brand")), brand.trim().toLowerCase(Locale.ROOT)));
            if (status != null) predicates.add(b.equal(r.get("status"), status));
            return b.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var result = products.findAll(spec, PageRequest.of(page, size, Sort.by("id")));
        return new PageView<>(result.getContent().stream().map(ProductView::from).toList(), result.getTotalElements(), page, size);
    }
    public ProductDetail getProduct(UUID id) {
        var product = ownedProduct(id);
        return new ProductDetail(ProductView.from(product), variants.findByProductIdAndMerchantIdOrderById(id,
                merchants.currentMerchantId()).stream().map(VariantView::from).toList());
    }
    @Transactional
    public ProductView updateProduct(UUID id, @NotNull @Valid UpdateProduct request) {
        var p = ownedProduct(id); p.update(request.name(), request.brand(), request.category(), request.status());
        return ProductView.from(p);
    }
    @Transactional
    public VariantView createVariant(UUID productId, @NotNull @Valid CreateVariant request) {
        var product = ownedProduct(productId);
        var attributes = normalizeAttributes(request.attributes());
        checkSku(request.sku().trim(), new UUID(0,0));
        var variant = variants.saveAndFlush(new ProductVariant(product, request.sku(), attributes, request.price(),
                request.status() == null ? CatalogStatus.ACTIVE : request.status()));
        inventories.save(new Inventory(merchants.currentMerchantId(), variant.getId()));
        return VariantView.from(variant);
    }
    @Transactional
    public VariantView updateVariant(UUID id, @NotNull @Valid UpdateVariant request) {
        var variant = ownedVariant(id);
        if (request.sku() != null) checkSku(request.sku().trim(), id);
        variant.update(request.sku(), request.attributes() == null ? null : normalizeAttributes(request.attributes()),
                request.price(), request.status());
        variants.flush();
        return VariantView.from(variant);
    }
    public SearchResult search(VariantCriteria criteria, int page, int size) {
        validatePage(page, size); validateText(criteria.productName(),200);
        validateText(criteria.brand(),100); validateText(criteria.sku(),100);
        var attributes = normalizeAttributes(criteria.attributes() == null ? Map.of() : criteria.attributes());
        UUID merchant = merchants.currentMerchantId();
        Specification<ProductVariant> spec = (r,q,b) -> {
            var p = r.join("product");
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(b.equal(r.get("merchantId"), merchant));
            predicates.add(b.equal(p.get("merchantId"), merchant));
            if (criteria.productName() != null) predicates.add(b.like(b.lower(p.get("name")),
                    "%" + escape(criteria.productName().trim().toLowerCase(Locale.ROOT)) + "%", '\\'));
            if (criteria.brand() != null) predicates.add(b.equal(b.lower(p.get("brand")), criteria.brand().trim().toLowerCase(Locale.ROOT)));
            if (criteria.sku() != null) predicates.add(b.equal(r.get("sku"), criteria.sku().trim()));
            if (criteria.status() != null) {
                predicates.add(b.equal(r.get("status"), criteria.status()));
                // Inactive parents cannot be resolved for future customer workflows.
                if (criteria.status() == CatalogStatus.ACTIVE) predicates.add(b.equal(p.get("status"), CatalogStatus.ACTIVE));
            }
            attributes.forEach((key,value) -> predicates.add(b.equal(b.lower(b.function("jsonb_extract_path_text", String.class,
                    r.get("attributes"), b.literal(key))), value.toLowerCase(Locale.ROOT))));
            return b.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var result = variants.findAll(spec, PageRequest.of(page, size, Sort.by("id")));
        long total = result.getTotalElements();
        var resolution = total == 0 ? Resolution.NOT_FOUND : total == 1 ? Resolution.MATCHED : Resolution.AMBIGUOUS;
        return new SearchResult(resolution, result.getContent().stream().map(VariantView::from).toList(), total, page, size);
    }
    public SearchResult resolveExactVariant(UUID id) {
        var variant = variants.findByIdAndMerchantId(id, merchants.currentMerchantId());
        if (variant.isEmpty() || variant.get().getStatus() != CatalogStatus.ACTIVE
                || variant.get().getProduct().getStatus() != CatalogStatus.ACTIVE)
            return new SearchResult(Resolution.NOT_FOUND, List.of(), 0, 0, 1);
        return new SearchResult(Resolution.MATCHED, List.of(VariantView.from(variant.get())), 1, 0, 1);
    }
    public InventoryView getInventory(UUID variantId) {
        return InventoryView.from(inventories.findByVariantIdAndMerchantId(variantId, merchants.currentMerchantId())
                .orElseThrow(ResourceNotFoundException::new));
    }
    @Transactional
    public InventoryUpdate updateInventory(UUID variantId, @NotNull @Valid UpdateInventory request) {
        var inventory = inventories.findForUpdate(variantId, merchants.currentMerchantId()).orElseThrow(ResourceNotFoundException::new);
        var event = inventory.setQuantity(request.quantity());
        event.ifPresent(events::save);
        return new InventoryUpdate(InventoryView.from(inventory), event.isPresent(),
                event.map(StockEvent::isRestockTransition).orElse(false), event.map(StockEvent::getId).orElse(null));
    }
    private Product ownedProduct(UUID id) { return products.findByIdAndMerchantId(id, merchants.currentMerchantId()).orElseThrow(ResourceNotFoundException::new); }
    private ProductVariant ownedVariant(UUID id) { return variants.findByIdAndMerchantId(id, merchants.currentMerchantId()).orElseThrow(ResourceNotFoundException::new); }
    private void checkSku(String sku, UUID except) {
        if (variants.existsByMerchantIdAndSkuAndIdNot(merchants.currentMerchantId(), sku, except)) throw new DuplicateSkuException();
    }
    public static Map<String,String> normalizeAttributes(Map<String,String> attributes) {
        if (attributes.size() > 20) throw new IllegalArgumentException("At most 20 attributes are allowed");
        var result = new TreeMap<String,String>();
        attributes.forEach((key,value) -> {
            if (key == null || value == null || !key.matches("[a-zA-Z][a-zA-Z0-9_-]{0,39}") || value.isBlank() || value.length() > 100)
                throw new IllegalArgumentException("Attributes require simple keys and nonblank values of at most 100 characters");
            String normalized = key.toLowerCase(Locale.ROOT);
            if (normalized.equals("colour")) normalized = "color";
            if (result.putIfAbsent(normalized, value.trim()) != null) throw new IllegalArgumentException("Duplicate normalized attribute key");
        });
        return result;
    }
    private static void validatePage(int page, int size) {
        if (page < 0 || page > 1000000 || size < 1 || size > 100) throw new IllegalArgumentException("Page must be 0..1000000 and size 1..100");
    }
    private static void validateText(String value, int max) {
        if (value != null && (value.isBlank() || value.length() > max)) throw new IllegalArgumentException("Invalid search field length");
    }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
}
