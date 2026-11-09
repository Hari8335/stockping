package dev.stockping.demand.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * External LLM extractor implementing constrained schema extraction via Google Gemini REST API.
 * Safely falls back to the deterministic rule extractor when credentials are missing, disabled,
 * or upon any network timeout or error.
 */
@Component
public class GeminiAiExtractor implements AiExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(GeminiAiExtractor.class);

    private final AiProperties properties;
    private final RuleBasedAiExtractor fallback;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public GeminiAiExtractor(AiProperties properties, RuleBasedAiExtractor fallback, ObjectMapper mapper) {
        this.properties = properties;
        this.fallback = fallback;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(5000, Math.max(500, properties.getTimeoutMs()))))
                .build();
    }

    @Override
    public ProductExtractionResult extract(String customerText, String correlationId) {
        long start = System.currentTimeMillis();

        if (!properties.isEnabled() || properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return fallback.extract(customerText, correlationId);
        }

        try {
            // Strip control characters and cap length to prevent prompt injection or excessive payload
            String sanitized = customerText.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", " ").strip();
            if (sanitized.length() > 500) sanitized = sanitized.substring(0, 500);

            String prompt = """
                    You are StockPing's customer inquiry extractor for retail electronics.
                    Extract structured product details from customer WhatsApp messages.
                    Classify intent into one of: CHECK_STOCK, JOIN_WAITLIST, OPT_OUT, HELP, UNKNOWN.
                    Extract brand, model, productPhrase, attributes (e.g. storage, color), and confidence score (0.0 to 1.0).
                    If the inquiry is too vague or lacks model details, set needsClarification=true and provide clarificationPrompt.
                    Never invent stock quantities or variants.
                    Return strictly valid JSON matching this schema:
                    {
                      "intent": "CHECK_STOCK",
                      "productPhrase": "S25 Ultra",
                      "brand": "Samsung",
                      "model": "Galaxy S25 Ultra",
                      "attributes": {"storage": "512GB", "color": "Black"},
                      "confidence": 0.94,
                      "needsClarification": false,
                      "clarificationPrompt": ""
                    }
                    Customer inquiry:
                    """ + "\"" + sanitized.replace("\"", "\\\"") + "\"";

            String requestBody = mapper.writeValueAsString(Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of("response_mime_type", "application/json", "temperature", 0.1)
            ));

            String url = properties.getApiUrl() + "/models/" + properties.getModel() + ":generateContent?key=" + properties.getApiKey();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;

            if (response.statusCode() != 200) {
                LOG.warn("Gemini API returned status={} latencyMs={} correlationId={}", response.statusCode(), latency, correlationId);
                return fallback.extract(customerText, correlationId);
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode candidateText = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (candidateText.isMissingNode() || candidateText.asText().isBlank()) {
                LOG.warn("Gemini response missing text part correlationId={}", correlationId);
                return fallback.extract(customerText, correlationId);
            }

            JsonNode parsed = mapper.readTree(candidateText.asText());
            String intentStr = parsed.path("intent").asText("UNKNOWN").toUpperCase(Locale.ROOT);
            AiIntent intent;
            try {
                intent = AiIntent.valueOf(intentStr);
            } catch (IllegalArgumentException e) {
                intent = AiIntent.UNKNOWN;
            }

            String productPhrase = parsed.path("productPhrase").asText("").trim();
            String brand = parsed.path("brand").asText("").trim();
            String model = parsed.path("model").asText("").trim();
            double confidence = parsed.path("confidence").asDouble(0.5);
            boolean needsClarification = parsed.path("needsClarification").asBoolean(false);
            String clarificationPrompt = parsed.path("clarificationPrompt").asText("").trim();

            Map<String, String> attributes = new TreeMap<>();
            JsonNode attrsNode = parsed.path("attributes");
            if (attrsNode.isObject()) {
                Map<String, String> map = mapper.convertValue(attrsNode, new tools.jackson.core.type.TypeReference<Map<String, String>>() {});
                if (map != null) {
                    map.forEach((k, v) -> {
                        if (k != null && v != null && !v.isBlank()) {
                            String key = k.toLowerCase(Locale.ROOT);
                            if (key.equals("colour")) key = "color";
                            attributes.put(key, v.trim());
                        }
                    });
                }
            }

            LOG.info("AI extraction completed intent={} confidence={} latencyMs={} correlationId={}",
                    intent, confidence, latency, correlationId);

            return new ProductExtractionResult(
                    intent, productPhrase, brand, model, attributes, confidence,
                    needsClarification, clarificationPrompt, properties.getModel(), latency
            );
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            LOG.warn("Gemini extraction error type={} latencyMs={} correlationId={}",
                    e.getClass().getSimpleName(), latency, correlationId);
            return fallback.extract(customerText, correlationId);
        }
    }
}
