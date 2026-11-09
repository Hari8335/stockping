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
    public record NotificationStock(String productId, String name, String sku, String description, boolean available) {}
    public record SearchCandidate(UUID variantId, UUID productId, String sku, String productName, String brand, Map<String, String> attributes) {}
    public record SearchOutcome(ResolutionStatus status, List<SearchCandidate> candidates, long totalMatches) {}
    public record VariantInventory(UUID variantId, int quantity, boolean available) {}
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

    public Optional<NotificationStock> notificationStock(UUID merchant, UUID variantId, String correlation) {
        if (token.length() < 32) throw unavailable();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("x-service-token", Metadata.ASCII_STRING_MARSHALLER), token);
        headers.put(Metadata.Key.of("x-merchant-id", Metadata.ASCII_STRING_MARSHALLER), merchant.toString());
        headers.put(Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER), correlation);
        var client = CatalogServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(timeout, TimeUnit.MILLISECONDS);
        try {
            var resolved = client.resolveProductVariant(ResolveProductVariantRequest.newBuilder()
                    .setMerchantId(merchant.toString()).setVariantId(variantId.toString()).build());
            if (resolved.getStatus() == ResolutionStatus.NOT_FOUND) return Optional.empty();
            if (resolved.getStatus() != ResolutionStatus.MATCHED || resolved.getCandidatesCount() != 1 || resolved.getTotalMatches() != 1)
                throw unavailable();
            var variant = resolved.getCandidates(0);
            if (!variantId.toString().equals(variant.getVariantId()) || variant.getProductName().isBlank() || variant.getSku().isBlank()) throw unavailable();
            var inventory = client.getInventory(GetInventoryRequest.newBuilder().setMerchantId(merchant.toString()).setVariantId(variantId.toString()).build());
            if (!inventory.getVariantId().equals(variantId.toString()) || inventory.getQuantity() < 0
                    || inventory.getAvailabilityStatus() != (inventory.getQuantity() > 0 ? AvailabilityStatus.AVAILABLE : AvailabilityStatus.UNAVAILABLE)) throw unavailable();
            String description = new TreeMap<>(variant.getAttributesMap()).entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue()).collect(java.util.stream.Collectors.joining(", "));
            return Optional.of(new NotificationStock(variant.getProductId(), variant.getProductName(), variant.getSku(),
                    description.isEmpty() ? variant.getSku() : description, inventory.getQuantity() > 0));
        } catch (StatusRuntimeException e) { throw unavailable(); }
    }

    public SearchOutcome search(UUID merchant, String productName, String brand, String sku, Map<String, String> attributes, String correlation) {
        if (token.length() < 32) throw unavailable();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("x-service-token", Metadata.ASCII_STRING_MARSHALLER), token);
        headers.put(Metadata.Key.of("x-merchant-id", Metadata.ASCII_STRING_MARSHALLER), merchant.toString());
        headers.put(Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER), correlation);
        var client = CatalogServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(timeout, TimeUnit.MILLISECONDS);
        try {
            var req = ResolveProductVariantRequest.newBuilder().setMerchantId(merchant.toString());
            if (productName != null && !productName.isBlank()) req.setProductName(productName.trim());
            if (brand != null && !brand.isBlank()) req.setBrand(brand.trim());
            if (sku != null && !sku.isBlank()) req.setSku(sku.trim());
            if (attributes != null && !attributes.isEmpty()) {
                attributes.forEach((k, v) -> {
                    if (k != null && v != null && !k.isBlank() && !v.isBlank()) {
                        String key = k.trim().toLowerCase(Locale.ROOT);
                        if (key.equals("colour")) key = "color";
                        req.putAttributes(key, v.trim());
                    }
                });
            }
            var resolved = client.resolveProductVariant(req.build());
            List<SearchCandidate> candidates = new ArrayList<>();
            for (var c : resolved.getCandidatesList()) {
                candidates.add(new SearchCandidate(
                        UUID.fromString(c.getVariantId()),
                        UUID.fromString(c.getProductId()),
                        c.getSku(),
                        c.getProductName(),
                        c.getBrand(),
                        new TreeMap<>(c.getAttributesMap())
                ));
            }
            return new SearchOutcome(resolved.getStatus(), candidates, resolved.getTotalMatches());
        } catch (StatusRuntimeException | IllegalArgumentException e) { throw unavailable(); }
    }

    public VariantInventory getInventory(UUID merchant, UUID variantId, String correlation) {
        if (token.length() < 32) throw unavailable();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("x-service-token", Metadata.ASCII_STRING_MARSHALLER), token);
        headers.put(Metadata.Key.of("x-merchant-id", Metadata.ASCII_STRING_MARSHALLER), merchant.toString());
        headers.put(Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER), correlation);
        var client = CatalogServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(timeout, TimeUnit.MILLISECONDS);
        try {
            var inventory = client.getInventory(GetInventoryRequest.newBuilder()
                    .setMerchantId(merchant.toString()).setVariantId(variantId.toString()).build());
            if (inventory.getQuantity() < 0) throw unavailable();
            return new VariantInventory(variantId, inventory.getQuantity(), inventory.getQuantity() > 0);
        } catch (StatusRuntimeException | IllegalArgumentException e) { throw unavailable(); }
    }
}
