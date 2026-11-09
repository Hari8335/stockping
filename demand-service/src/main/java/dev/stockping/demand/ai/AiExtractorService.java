package dev.stockping.demand.ai;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
public class AiExtractorService implements AiExtractor {

    private final AiProperties properties;
    private final RuleBasedAiExtractor ruleExtractor;
    private final GeminiAiExtractor geminiExtractor;

    public AiExtractorService(AiProperties properties, RuleBasedAiExtractor ruleExtractor, GeminiAiExtractor geminiExtractor) {
        this.properties = properties;
        this.ruleExtractor = ruleExtractor;
        this.geminiExtractor = geminiExtractor;
    }

    @Override
    public ProductExtractionResult extract(String customerText, String correlationId) {
        if (!properties.isEnabled() && !"mock".equalsIgnoreCase(properties.getProvider())) {
            return ruleExtractor.extract(customerText, correlationId);
        }

        if ("gemini".equalsIgnoreCase(properties.getProvider())) {
            return geminiExtractor.extract(customerText, correlationId);
        }

        return ruleExtractor.extract(customerText, correlationId);
    }
}
