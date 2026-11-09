package dev.stockping.contracts.events;

/** Request is deliberately PII-free. Messaging obtains current eligibility over authenticated gRPC. */
public record NotificationRequested(String eventId, String eventType, int eventVersion, String occurredAt,
        String merchantId, String notificationId, String availabilityEventId, String correlationId) {
    public static final String TYPE = "notification.requested.v1";
}
