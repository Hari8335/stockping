package dev.stockping.catalog.grpc;

import dev.stockping.catalog.config.MerchantContextProvider;
import dev.stockping.catalog.domain.CatalogStatus;
import dev.stockping.catalog.exception.ResourceNotFoundException;
import dev.stockping.catalog.service.*;
import dev.stockping.contracts.catalog.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(CatalogGrpcService.class);
    private final CatalogService catalog;
    private final MerchantContextProvider merchants;
    public CatalogGrpcService(CatalogService catalog, MerchantContextProvider merchants) {
        this.catalog = catalog; this.merchants = merchants;
    }
    @Override public void resolveProductVariant(ResolveProductVariantRequest request, StreamObserver<ResolveProductVariantResponse> observer) {
        respond(observer, () -> {
            assertMerchant(request.getMerchantId());
            if (request.getProductName().isBlank() && request.getBrand().isBlank() && request.getSku().isBlank() && request.getAttributesCount() == 0)
                throw new IllegalArgumentException("At least one lookup field is required");
            var result = catalog.search(new VariantCriteria(optional(request.getProductName()), optional(request.getBrand()),
                    optional(request.getSku()), CatalogStatus.ACTIVE, request.getAttributesMap()), 0, 100);
            var response = ResolveProductVariantResponse.newBuilder()
                    .setStatus(ResolutionStatus.valueOf(result.resolutionStatus().name())).setTotalMatches(result.totalMatches());
            result.candidates().forEach(v -> response.addCandidates(Variant.newBuilder().setVariantId(v.id().toString())
                    .setProductId(v.productId().toString()).setSku(v.sku()).setProductName(v.productName())
                    .setBrand(v.brand()).putAllAttributes(v.attributes())));
            return response.build();
        });
    }
    @Override public void getInventory(GetInventoryRequest request, StreamObserver<GetInventoryResponse> observer) {
        respond(observer, () -> {
            assertMerchant(request.getMerchantId());
            var result = catalog.getInventory(parseId(request.getVariantId()));
            return GetInventoryResponse.newBuilder().setVariantId(result.variantId().toString())
                    .setQuantity(result.quantity()).setAvailabilityStatus(AvailabilityStatus.valueOf(result.availabilityStatus().name()))
                    .setUpdatedAt(result.updatedAt().toString()).build();
        });
    }
    private void assertMerchant(String value) {
        if (!parseId(value).equals(merchants.currentMerchantId())) throw new ResourceNotFoundException();
    }
    private static UUID parseId(String value) {
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("Invalid UUID");
        return UUID.fromString(value);
    }
    private static String optional(String value) { return value.isEmpty() ? null : value; }
    private static <T> void respond(StreamObserver<T> observer, Supplier<T> action) {
        try { observer.onNext(action.get()); observer.onCompleted(); }
        catch (ResourceNotFoundException e) { observer.onError(Status.NOT_FOUND.withDescription("Resource not found").asRuntimeException()); }
        catch (IllegalArgumentException | jakarta.validation.ConstraintViolationException e) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("Invalid request").asRuntimeException());
        }
        catch (Exception e) {
            LOG.error("Unexpected Catalog RPC failure",e);
            observer.onError(Status.INTERNAL.withDescription("An unexpected error occurred").asRuntimeException());
        }
    }
}
