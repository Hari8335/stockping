package dev.stockping.catalog.exception;
public class DuplicateSkuException extends RuntimeException {
    public DuplicateSkuException() { super("SKU already exists for this merchant"); }
}
