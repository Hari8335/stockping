package dev.stockping.demand.dto;

import dev.stockping.demand.domain.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class DemandDtos {
    private DemandDtos() {}
    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password) {
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }
    public record LoginResponse(String accessToken, Instant expiresAt, UUID userId, UUID merchantId, String role) {
        @Override public String toString() { return "LoginResponse[redacted]"; }
    }
    public record CreateWaitlistRequest(@NotBlank @Size(max = 40) String customerPhone,
            @Size(max = 120) String displayName, @Pattern(regexp = "[a-zA-Z-]{2,16}") String language,
            @NotNull UUID variantId, @NotNull Boolean consent) {}
    public record WaitlistView(UUID id, UUID customerId, String customerPhone, String displayName, UUID variantId,
            WaitlistStatus status, Instant createdAt, Instant updatedAt, Instant notifiedAt,
            ConsentStatus consentStatus, boolean eligible) {}
    public record ConsentView(UUID customerId, ConsentStatus status, Instant capturedAt, int updatedWaitlists) {}
    public record PageView<T>(List<T> items, long totalElements, int page, int size) {}
    public record VariantDemand(UUID variantId, long requestCount, long waitingCount, long activeCount) {}
    public record DemandSummary(long totalActive, long totalWaiting, List<VariantDemand> variants) {}
}
