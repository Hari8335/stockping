package dev.stockping.demand;
import dev.stockping.demand.service.PhoneNormalizer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class PhoneNormalizerTest {
    final PhoneNormalizer phones = new PhoneNormalizer();
    @ParameterizedTest
    @ValueSource(strings = {"+94771234567", "+94 77 123 4567", "+94 (77) 123-4567"})
    void normalizesDeterministically(String value) {
        assertEquals("+94771234567", phones.normalize(value));
        assertEquals(phones.normalize(value), phones.normalize(phones.normalize(value)));
    }
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0771234567", "0094771234567", "+0123456789", "+123", "+1234567890123456", "+94abc771234567", "++94771234567", "+94771234567 ext 2"})
    void rejectsMalformedOrAmbiguousNumbers(String value) { assertThrows(IllegalArgumentException.class, () -> phones.normalize(value)); }
}
