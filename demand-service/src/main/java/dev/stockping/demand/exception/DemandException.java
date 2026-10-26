package dev.stockping.demand.exception;

public class DemandException extends RuntimeException {
    private final int status;
    private final String code;
    public DemandException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static DemandException missing() { return new DemandException(404, "NOT_FOUND", "Resource not found"); }
}
