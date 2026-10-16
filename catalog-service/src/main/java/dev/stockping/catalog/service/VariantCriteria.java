package dev.stockping.catalog.service;
import dev.stockping.catalog.domain.CatalogStatus;
import java.util.Map;
public record VariantCriteria(String productName, String brand, String sku, CatalogStatus status, Map<String,String> attributes) {}
