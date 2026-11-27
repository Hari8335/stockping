package dev.stockping.demand.ai;

import java.util.Locale;

public final class TanglishSupport {
    private TanglishSupport() {}

    public static boolean isTanglish(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\b(iruka|irukka|irukaa|colour|vandha|sollunga|enna|kidaikuma|venuma)\\b.*")
                || lower.matches(".*\\bavailable\\s+ah\\b.*");
    }

    public static String clarification(boolean hasAttributes) {
        return hasAttributes
                ? "Endha phone model venum? Color and storage sollunga."
                : "Endha phone model, color, and storage venum? Sollunga.";
    }

    public static String priceUnsupported() {
        return "Price details inga support illa. Model or SKU sollunga; stock check pannuren.";
    }

    public static String available(String product, String sku) {
        return product + " (" + sku + ") ippo stock-la irukku.";
    }

    public static String outOfStock(String product, String sku) {
        return product + " (" + sku + ") ippo stock-la illa. Stock vandha sollattuma? YES or WAITLIST " + sku + " anuppunga.";
    }

    public static String ambiguous(String options) {
        return "Multiple options irukku: " + options + ". Color or storage sollunga.";
    }

    public static String notFound(String product) {
        return "'" + product + "' catalog-la kidaikkala. Model name or SKU check pannunga.";
    }
}
