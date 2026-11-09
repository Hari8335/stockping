package dev.stockping.demand.ai;

public interface AiExtractor {
    ProductExtractionResult extract(String customerText, String correlationId);
}
