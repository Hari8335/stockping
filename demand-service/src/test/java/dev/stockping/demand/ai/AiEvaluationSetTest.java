package dev.stockping.demand.ai;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SRS Section 17.2 AI Evaluation Set Test.
 * Validates constrained natural-language extraction across standard English,
 * abbreviations, colloquial slang, ambiguous queries, and opt-outs.
 */
class AiEvaluationSetTest {

    public record TestCase(
            String id,
            String query,
            String category,
            String expectedIntent,
            String expectedBrand,
            String expectedModel,
            String expectedStorage,
            String expectedColor,
            Double minConfidence,
            boolean expectClarification
    ) {}

    private RuleBasedAiExtractor extractor;
    private ObjectMapper mapper;

    @BeforeEach
    void setup() {
        extractor = new RuleBasedAiExtractor();
        mapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Evaluate full test set from SRS 17.2 against the constrained extractor")
    void evaluateAiTestSet() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/ai-evaluation-set.json")) {
            assertNotNull(is, "ai-evaluation-set.json resource must be present");
            List<TestCase> cases = mapper.readValue(is, new TypeReference<List<TestCase>>() {});
            assertFalse(cases.isEmpty(), "Test cases must not be empty");

            int passed = 0;
            for (TestCase tc : cases) {
                ProductExtractionResult res = extractor.extract(tc.query(), "test-" + tc.id());
                assertNotNull(res, "Result must not be null for " + tc.id());

                // Check intent
                assertEquals(AiIntent.valueOf(tc.expectedIntent()), res.intent(),
                        "Intent mismatch for " + tc.id() + ": " + tc.query());

                // Check clarification flag
                assertEquals(tc.expectClarification(), res.needsClarification(),
                        "Clarification flag mismatch for " + tc.id());

                // Check confidence threshold
                if (tc.minConfidence() != null) {
                    assertTrue(res.confidence() >= tc.minConfidence(),
                            "Confidence " + res.confidence() + " below " + tc.minConfidence() + " for " + tc.id());
                }

                // Check extracted brand & model if expected
                if (tc.expectedBrand() != null && !tc.expectedBrand().isBlank()) {
                    assertEquals(tc.expectedBrand(), res.brand(), "Brand mismatch for " + tc.id());
                }
                if (tc.expectedModel() != null && !tc.expectedModel().isBlank()) {
                    assertEquals(tc.expectedModel(), res.model(), "Model mismatch for " + tc.id());
                }

                // Check extracted attributes
                Map<String, String> attrs = res.attributes();
                if (tc.expectedStorage() != null && !tc.expectedStorage().isBlank()) {
                    assertEquals(tc.expectedStorage(), attrs.get("storage"), "Storage mismatch for " + tc.id());
                }
                if (tc.expectedColor() != null && !tc.expectedColor().isBlank()) {
                    assertEquals(tc.expectedColor(), attrs.get("color"), "Color mismatch for " + tc.id());
                }

                passed++;
            }

            assertEquals(cases.size(), passed, "All evaluation set test cases must pass");
        }
    }

    @Test
    void emptyOrBlankQueryReturnsUnknown() {
        ProductExtractionResult res = extractor.extract("   ", "corr-empty");
        assertEquals(AiIntent.UNKNOWN, res.intent());
        assertTrue(res.needsClarification());
    }

    @Test
    void queryWithNoModelAsksClarification() {
        ProductExtractionResult res = extractor.extract("Do you have silver 256gb available?", "corr-clarify");
        assertTrue(res.needsClarification());
        assertEquals("Which phone model are you looking for?", res.clarificationPrompt());
        assertEquals("Silver", res.attributes().get("color"));
        assertEquals("256GB", res.attributes().get("storage"));
    }
}
