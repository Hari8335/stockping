package dev.stockping.demand.grpc;

import dev.stockping.contracts.catalog.v1.*;
import dev.stockping.demand.exception.DemandException;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Uses a dedicated read-only service credential, never fabricates a merchant-admin JWT. */
@Component
public class MessageCatalogClient {
    public record Stock(String name, boolean available) {}
    private final ManagedChannel channel;
    private final String token;
    private final long timeout;
    public MessageCatalogClient(ManagedChannel channel, @Value("${catalog.service-token:}") String token,
                                @Value("${catalog.grpc-timeout-ms}") long timeout) {
        this.channel = channel; this.token = token; this.timeout = timeout;
    }
    public Optional<Stock> lookup(UUID merchant, String sku, String correlation) {
        if (token.length() < 32) throw unavailable();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("x-service-token", Metadata.ASCII_STRING_MARSHALLER), token);
        headers.put(Metadata.Key.of("x-merchant-id", Metadata.ASCII_STRING_MARSHALLER), merchant.toString());
        headers.put(Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER), correlation);
        var client = CatalogServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers)).withDeadlineAfter(timeout, TimeUnit.MILLISECONDS);
        try {
            var resolved = client.resolveProductVariant(ResolveProductVariantRequest.newBuilder()
                    .setMerchantId(merchant.toString()).setSku(sku).build());
            if (resolved.getStatus() == ResolutionStatus.NOT_FOUND) return Optional.empty();
            if (resolved.getStatus() != ResolutionStatus.MATCHED || resolved.getCandidatesCount() != 1 || resolved.getTotalMatches() != 1)
                throw unavailable();
            var variant = resolved.getCandidates(0);
            if (!sku.equals(variant.getSku()) || variant.getProductName().isBlank()) throw unavailable();
            UUID.fromString(variant.getVariantId());
            var inventory = client.getInventory(GetInventoryRequest.newBuilder().setMerchantId(merchant.toString())
                    .setVariantId(variant.getVariantId()).build());
            if (!inventory.getVariantId().equals(variant.getVariantId()) || inventory.getQuantity() < 0 ||
                    inventory.getAvailabilityStatus() != (inventory.getQuantity() > 0 ? AvailabilityStatus.AVAILABLE : AvailabilityStatus.UNAVAILABLE))
                throw unavailable();
            return Optional.of(new Stock(variant.getProductName(), inventory.getQuantity() > 0));
        } catch (StatusRuntimeException | IllegalArgumentException e) { throw unavailable(); }
    }
    private static DemandException unavailable() { return new DemandException(503, "CATALOG_UNAVAILABLE", "Stock could not be verified"); }
}
