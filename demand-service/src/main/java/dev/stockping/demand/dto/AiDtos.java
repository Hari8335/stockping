package dev.stockping.demand.dto;

import dev.stockping.demand.ai.AiIntent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

public final class AiDtos {
    private AiDtos() {}

    public record AiQueryRequest(
            @NotBlank @Size(max = 500) String query
    ) {}

    public record ExtractedDetails(
            String productPhrase,
            String brand,
            String model,
            Map<String, String> attributes
    ) {}

    public record MatchedVariantView(
            UUID variantId,
            String sku,
            String productName,
            String brand,
            Map<String, String> attributes,
            boolean available,
            int quantity
    ) {}

    public record AiQueryResponse(
            String query,
            AiIntent intent,
            double confidence,
            boolean needsClarification,
            String clarificationPrompt,
            ExtractedDetails extracted,
            String catalogStatus,
            MatchedVariantView matchedVariant,
            String suggestedReply,
            String purpose,
            String disclaimer,
            String provider,
            long latencyMs
    ) {}
}
