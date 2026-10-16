package dev.stockping.catalog.controller;

import dev.stockping.catalog.domain.CatalogStatus;
import dev.stockping.catalog.dto.CatalogDtos.*;
import dev.stockping.catalog.service.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class CatalogController {
    private final CatalogService catalog;
    public CatalogController(CatalogService catalog) { this.catalog = catalog; }
    @PostMapping("/products")
    public ResponseEntity<ProductView> createProduct(@Valid @RequestBody CreateProduct request) {
        var product = catalog.createProduct(request);
        return ResponseEntity.created(URI.create("/api/products/" + product.id())).body(product);
    }
    @GetMapping("/products")
    public PageView<ProductView> products(@RequestParam(required=false) String name, @RequestParam(required=false) String brand,
            @RequestParam(required=false) CatalogStatus status, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return catalog.listProducts(name, brand, status, page, size);
    }
    @GetMapping("/products/{id}") public ProductDetail product(@PathVariable UUID id) { return catalog.getProduct(id); }
    @PatchMapping("/products/{id}") public ProductView updateProduct(@PathVariable UUID id, @Valid @RequestBody UpdateProduct request) {
        return catalog.updateProduct(id, request);
    }
    @PostMapping("/products/{id}/variants")
    public ResponseEntity<VariantView> createVariant(@PathVariable UUID id, @Valid @RequestBody CreateVariant request) {
        var variant = catalog.createVariant(id, request);
        return ResponseEntity.created(URI.create("/api/products/" + id)).body(variant);
    }
    @PatchMapping("/variants/{id}") public VariantView updateVariant(@PathVariable UUID id, @Valid @RequestBody UpdateVariant request) {
        return catalog.updateVariant(id, request);
    }
    @GetMapping("/variants/search")
    public SearchResult search(@RequestParam(required=false) String productName, @RequestParam(required=false) String brand,
            @RequestParam(required=false) String sku, @RequestParam(required=false) CatalogStatus status,
            @RequestParam Map<String,String> params, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        var attributes = new TreeMap<String,String>();
        params.forEach((key,value) -> {
            if (key.startsWith("attribute.")) attributes.put(key.substring(10), value);
            else if (key.equals("storage") || key.equals("color") || key.equals("colour")) attributes.put(key,value);
            else if (!Set.of("productName","brand","sku","status","page","size").contains(key))
                throw new IllegalArgumentException("Unknown search parameter");
        });
        return catalog.search(new VariantCriteria(productName,brand,sku,status,attributes),page,size);
    }
    @GetMapping("/variants/{id}/inventory") public InventoryView inventory(@PathVariable UUID id) { return catalog.getInventory(id); }
    @PatchMapping("/variants/{id}/inventory") public InventoryUpdate updateInventory(@PathVariable UUID id, @Valid @RequestBody UpdateInventory request) {
        return catalog.updateInventory(id, request);
    }
}
