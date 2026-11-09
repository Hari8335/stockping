package dev.stockping.demand.ai;

import java.util.Map;

public record ProductExtractionResult(
        AiIntent intent,
        String productPhrase,
        String brand,
        String model,
        Map<String, String> attributes,
        double confidence,
        boolean needsClarification,
        String clarificationPrompt,
        String providerModel,
        long latencyMs
) {
    public static ProductExtractionResult unknown(String prompt, String provider) {
        return new ProductExtractionResult(
                AiIntent.UNKNOWN, "", "", "", Map.of(), 0.0, true,
                prompt != null ? prompt : "I could not identify the product you are asking about.",
                provider != null ? provider : "fallback", 0L
        );
    }
}
