package dev.stockping.demand.ai;

import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

/**
 * Deterministic rule-based extractor for constrained customer inquiries.
 * Acts as the default provider in local/test environments and provides safe fallback
 * when external LLM calls are disabled, unavailable, or time out.
 */
@Component
public class RuleBasedAiExtractor implements AiExtractor {

    private static final Pattern OPT_OUT_PATTERN = Pattern.compile("(?i)\\b(stop|unsubscribe|cancel|opt\\s*out|dont\\s*send|don't\\s*send|stop\\s*sending)\\b");
    private static final Pattern HELP_PATTERN = Pattern.compile("(?i)\\b(help|commands|how\\s*does\\s*this\\s*work|info|menu)\\b");
    private static final Pattern WAITLIST_PATTERN = Pattern.compile("(?i)\\b(waitlist|notify\\s*me|alert\\s*me|join\\s*waitlist|add\\s*me)\\b");

    private static final Pattern STORAGE_PATTERN = Pattern.compile("(?i)\\b(128|256|512|1024)\\s*(?:gb|g)?\\b|\\b(1|2)\\s*tb\\b");
    private static final Pattern COLOR_PATTERN = Pattern.compile("(?i)\\b(black|blk|silver|white|grey|gray|titanium|blue|gold|green|purple)\\b");

    @Override
    public ProductExtractionResult extract(String customerText, String correlationId) {
        long start = System.currentTimeMillis();
        if (customerText == null || customerText.isBlank()) {
            return ProductExtractionResult.unknown("Message text is empty.", "rule-based");
        }

        String raw = customerText.strip();
        String lower = raw.toLowerCase(Locale.ROOT);

        // 1. Check Opt-out intent
        if (OPT_OUT_PATTERN.matcher(lower).find()) {
            return new ProductExtractionResult(
                    AiIntent.OPT_OUT, "", "", "", Map.of(), 0.99, false, "", "rule-based",
                    System.currentTimeMillis() - start
            );
        }

        // 2. Check Help intent
        if (HELP_PATTERN.matcher(lower).find() && !lower.contains("s25") && !lower.contains("iphone")) {
            return new ProductExtractionResult(
                    AiIntent.HELP, "", "", "", Map.of(), 0.95, false, "", "rule-based",
                    System.currentTimeMillis() - start
            );
        }

        // 3. Extract attributes
        Map<String, String> attributes = new TreeMap<>();
        var storageMatcher = STORAGE_PATTERN.matcher(lower);
        if (storageMatcher.find()) {
            String val = storageMatcher.group().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
            if (val.matches("\\d+")) val += "GB";
            else if (val.endsWith("G")) val += "B";
            attributes.put("storage", val);
        }

        var colorMatcher = COLOR_PATTERN.matcher(lower);
        if (colorMatcher.find()) {
            String c = colorMatcher.group().toLowerCase(Locale.ROOT);
            String normalizedColor = switch (c) {
                case "blk", "black" -> "Black";
                case "silver" -> "Silver";
                case "white" -> "White";
                case "gray", "grey" -> "Grey";
                case "titanium" -> "Titanium";
                case "blue" -> "Blue";
                case "gold" -> "Gold";
                case "green" -> "Green";
                case "purple" -> "Purple";
                default -> Character.toUpperCase(c.charAt(0)) + c.substring(1);
            };
            attributes.put("color", normalizedColor);
        }

        // 4. Identify product & brand
        String brand = "";
        String model = "";
        String productPhrase = "";
        double confidence = 0.50;

        if (lower.contains("s25 ultra") || lower.contains("s25u") || lower.contains("s25-ultra") ||
                (lower.contains("s25") && lower.contains("ultra"))) {
            brand = "Samsung";
            model = "Galaxy S25 Ultra";
            productPhrase = "S25 Ultra";
            confidence = (attributes.containsKey("storage") && attributes.containsKey("color")) ? 0.94 : 0.88;
        } else if (lower.contains("s25") || lower.contains("galaxy s25")) {
            brand = "Samsung";
            model = "Galaxy S25";
            productPhrase = "Galaxy S25";
            confidence = (attributes.containsKey("storage") && attributes.containsKey("color")) ? 0.92 : 0.85;
        } else if (lower.contains("iphone 17 pro") || lower.contains("iphone 17pro") || lower.contains("17 pro")) {
            brand = "Apple";
            model = "iPhone 17 Pro";
            productPhrase = "iPhone 17 Pro";
            confidence = (attributes.containsKey("storage") && attributes.containsKey("color")) ? 0.94 : 0.88;
        } else if (lower.contains("iphone 17") || lower.contains("iphone17")) {
            brand = "Apple";
            model = "iPhone 17";
            productPhrase = "iPhone 17";
            confidence = (attributes.containsKey("storage") && attributes.containsKey("color")) ? 0.92 : 0.85;
        } else if (lower.contains("samsung")) {
            brand = "Samsung";
            confidence = 0.60;
        } else if (lower.contains("iphone") || lower.contains("apple")) {
            brand = "Apple";
            confidence = 0.60;
        }

        // 5. Check if inquiry lacks a specific model (clarification required)
        if (model.isBlank()) {
            if (!attributes.isEmpty()) {
                // Customer specified attributes like "silver 512" without a model
                return new ProductExtractionResult(
                        AiIntent.CHECK_STOCK, "", brand, "", attributes, 0.45, true,
                        "Which phone model are you looking for?", "rule-based",
                        System.currentTimeMillis() - start
                );
            }
            return ProductExtractionResult.unknown(
                    "I could not identify the product you are asking about. Please provide the model name or SKU.", "rule-based"
            );
        }

        // 6. Intent classification
        AiIntent intent = WAITLIST_PATTERN.matcher(lower).find() ? AiIntent.JOIN_WAITLIST : AiIntent.CHECK_STOCK;

        return new ProductExtractionResult(
                intent, productPhrase, brand, model, attributes, confidence, false, "", "rule-based",
                System.currentTimeMillis() - start
        );
    }
}
