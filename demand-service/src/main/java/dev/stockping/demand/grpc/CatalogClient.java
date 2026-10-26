package dev.stockping.demand.grpc;

import dev.stockping.contracts.catalog.v1.*;
import dev.stockping.demand.exception.DemandException;
import dev.stockping.security.Identity;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CatalogClient {
    private final ManagedChannel channel;
    private final long timeoutMillis;
    public CatalogClient(ManagedChannel channel, @Value("${catalog.grpc-timeout-ms}") long timeoutMillis) {
        if (timeoutMillis < 50 || timeoutMillis > 10000) throw new IllegalArgumentException("Catalog deadline must be 50..10000ms");
        this.channel = channel; this.timeoutMillis = timeoutMillis;
    }

    /** One overall deadline bounds both RPCs. Only Catalog can confirm an exact unavailable variant. */
    public void verifyUnavailable(Identity identity, UUID variant) {
        var metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + identity.token());
        String correlation = org.slf4j.MDC.get("request.id");
        if (correlation != null) metadata.put(Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER), correlation);
        var client = CatalogServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS);
        try {
            var resolved = client.resolveProductVariant(ResolveProductVariantRequest.newBuilder()
                    .setMerchantId(identity.merchantId().toString()).setVariantId(variant.toString()).build());
            switch (resolved.getStatus()) {
                case NOT_FOUND -> throw new DemandException(404, "VARIANT_NOT_FOUND", "Variant not found");
                case AMBIGUOUS -> throw new DemandException(409, "AMBIGUOUS_VARIANT", "An exact variant must be selected");
                case MATCHED -> {
                    if (resolved.getTotalMatches() != 1 || resolved.getCandidatesCount() != 1
                            || !resolved.getCandidates(0).getVariantId().equals(variant.toString())) throw invalidResponse();
                }
                default -> throw invalidResponse();
            }
            var stock = client.getInventory(GetInventoryRequest.newBuilder()
                    .setMerchantId(identity.merchantId().toString()).setVariantId(variant.toString()).build());
            if (!stock.getVariantId().equals(variant.toString()) || stock.getQuantity() < 0
                    || stock.getAvailabilityStatus() != (stock.getQuantity() == 0 ? AvailabilityStatus.UNAVAILABLE : AvailabilityStatus.AVAILABLE))
                throw invalidResponse();
            if (stock.getQuantity() > 0) throw new DemandException(409, "ITEM_AVAILABLE", "Catalog confirms this item is already available");
        } catch (StatusRuntimeException e) {
            throw switch (e.getStatus().getCode()) {
                case NOT_FOUND -> new DemandException(404, "VARIANT_NOT_FOUND", "Variant not found");
                case INVALID_ARGUMENT -> new DemandException(400, "CATALOG_INVALID_REQUEST", "Catalog rejected the lookup");
                case UNAVAILABLE -> new DemandException(503, "CATALOG_UNAVAILABLE", "Catalog is temporarily unavailable; try again");
                case DEADLINE_EXCEEDED -> new DemandException(504, "CATALOG_TIMEOUT", "Catalog did not respond in time; try again");
                case UNAUTHENTICATED -> new DemandException(401, "INVALID_TOKEN", "Authentication expired or was rejected");
                case PERMISSION_DENIED -> new DemandException(403, "FORBIDDEN", "Catalog access denied");
                default -> new DemandException(502, "CATALOG_ERROR", "Catalog could not verify the item");
            };
        }
    }

    private static DemandException invalidResponse() {
        return new DemandException(502, "CATALOG_INVALID_RESPONSE", "Catalog could not verify the item");
    }
}
