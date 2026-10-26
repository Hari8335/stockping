package dev.stockping.demand.service;

import org.springframework.stereotype.Component;

@Component
public class PhoneNormalizer {
    /** Explicit international numbers only. No country inference or local-number guessing. */
    public String normalize(String value) {
        if (value == null || value.length() > 40 || !value.matches("\\+?[0-9 ()-]+"))
            throw new IllegalArgumentException("Use an international phone number starting with +");
        String normalized = value.replaceAll("[ ()-]", "");
        if (!normalized.matches("\\+[1-9][0-9]{7,14}"))
            throw new IllegalArgumentException("Use + followed by 8 to 15 digits");
        return normalized;
    }
}
