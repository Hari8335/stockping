package dev.stockping.catalog.domain;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class InventoryTest {
    @ParameterizedTest
    @CsvSource({"0,5,AVAILABLE,true", "5,10,AVAILABLE,false", "5,0,UNAVAILABLE,false"})
    void actualChangeCreatesAuditableTransition(int previous, int next, Availability expected, boolean restock) {
        var merchant = UUID.randomUUID(); var variant = UUID.randomUUID();
        var inventory = new Inventory(merchant, variant);
        inventory.setQuantity(previous);
        var event = inventory.setQuantity(next).orElseThrow();
        assertEquals(next, inventory.getQuantity());
        assertEquals(expected, inventory.getAvailabilityStatus());
        assertEquals(previous, event.getPreviousQuantity());
        assertEquals(next, event.getNewQuantity());
        assertEquals(restock, event.isRestockTransition());
        assertEquals(merchant, event.getMerchantId());
        assertEquals(variant, event.getVariantId());
        assertEquals("MANUAL", event.getSource());
        assertEquals(event.getCreatedAt(), inventory.getUpdatedAt());
    }

    @ParameterizedTest @CsvSource({"0", "5"})
    void unchangedQuantityCreatesNoEventAndPreservesTimestamp(int quantity) {
        var inventory = new Inventory(UUID.randomUUID(), UUID.randomUUID());
        inventory.setQuantity(quantity); var timestamp = inventory.getUpdatedAt();
        assertTrue(inventory.setQuantity(quantity).isEmpty());
        assertEquals(timestamp, inventory.getUpdatedAt());
    }

    @Test void rejectsNegativeQuantityWithoutMutation() {
        var inventory = new Inventory(UUID.randomUUID(), UUID.randomUUID());
        var timestamp = inventory.getUpdatedAt();
        assertThrows(IllegalArgumentException.class, () -> inventory.setQuantity(-1));
        assertEquals(0, inventory.getQuantity());
        assertEquals(Availability.UNAVAILABLE, inventory.getAvailabilityStatus());
        assertEquals(timestamp, inventory.getUpdatedAt());
    }
}
