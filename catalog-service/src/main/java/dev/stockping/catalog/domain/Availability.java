package dev.stockping.catalog.domain;
public enum Availability { AVAILABLE, UNAVAILABLE;
    public static Availability fromQuantity(int quantity) {
        if (quantity < 0) throw new IllegalArgumentException("Quantity cannot be negative");
        return quantity > 0 ? AVAILABLE : UNAVAILABLE;
    }
}
