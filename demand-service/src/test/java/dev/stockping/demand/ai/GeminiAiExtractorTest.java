package dev.stockping.demand.ai;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeminiAiExtractorTest {

    private AiProperties properties;
    private RuleBasedAiExtractor fallback;
    private ObjectMapper mapper;

    @BeforeEach
    void setup() {
        properties = new AiProperties();
        fallback = new RuleBasedAiExtractor();
        mapper = new ObjectMapper();
    }

    @Test
    void disabledPropertiesFallsBackToRuleBased() {
        properties.setEnabled(false);
        GeminiAiExtractor extractor = new GeminiAiExtractor(properties, fallback, mapper);

        ProductExtractionResult result = extractor.extract("S25 Ultra 512 black available?", "corr-1");
        assertNotNull(result);
        assertEquals(AiIntent.CHECK_STOCK, result.intent());
        assertEquals("Samsung", result.brand());
        assertEquals("Galaxy S25 Ultra", result.model());
    }

    @Test
    void missingApiKeyFallsBackToRuleBased() {
        properties.setEnabled(true);
        properties.setApiKey("");
        GeminiAiExtractor extractor = new GeminiAiExtractor(properties, fallback, mapper);

        ProductExtractionResult result = extractor.extract("stop sending alerts", "corr-2");
        assertNotNull(result);
        assertEquals(AiIntent.OPT_OUT, result.intent());
    }

    @Test
    void invalidUrlFallsBackGracefullyWithoutCrashing() {
        properties.setEnabled(true);
        properties.setApiKey("fake-key-for-test");
        properties.setApiUrl("http://localhost:59999/non-existent");
        properties.setTimeoutMs(500);
        GeminiAiExtractor extractor = new GeminiAiExtractor(properties, fallback, mapper);

        ProductExtractionResult result = extractor.extract("Need iphone 17 pro 256 black", "corr-3");
        assertNotNull(result);
        assertEquals(AiIntent.CHECK_STOCK, result.intent());
        assertEquals("Apple", result.brand());
    }
}
