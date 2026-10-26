package dev.stockping.demand;

import dev.stockping.contracts.catalog.v1.*;
import dev.stockping.security.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real TCP gRPC server, generated contract, signed tenant validation, controllable failure modes. */
public final class TestCatalog implements AutoCloseable {
    public record Item(UUID merchant, int quantity) {}
    public final Map<UUID, Item> items = new ConcurrentHashMap<>();
    public final AtomicInteger resolutions = new AtomicInteger(), inventories = new AtomicInteger();
    public volatile Status failure;
    public volatile ResolutionStatus resolution;
    public volatile boolean corruptStock;
    public volatile long delayMillis;
    public volatile boolean bounded;
    private final Server server;
    private static final Context.Key<Identity> IDENTITY = Context.key("test-identity");

    public TestCatalog() {
        var config = new TokenConfiguration();
        var decoder = config.jwtDecoder(config.signingKey(TestTokens.SECRET));
        ServerInterceptor auth = new ServerInterceptor() {
            @Override public <Q, S> ServerCall.Listener<Q> interceptCall(ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
                try {
                    String token = headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER));
                    Identity identity = Identity.from(decoder.decode(token.substring(7)));
                    return Contexts.interceptCall(Context.current().withValue(IDENTITY, identity), call, headers, next);
                } catch (RuntimeException e) {
                    call.close(Status.UNAUTHENTICATED, new Metadata());
                    return new ServerCall.Listener<>() {};
                }
            }
        };
        var handler = new CatalogServiceGrpc.CatalogServiceImplBase() {
            @Override public void resolveProductVariant(ResolveProductVariantRequest request, StreamObserver<ResolveProductVariantResponse> observer) {
                resolutions.incrementAndGet();
                if (!ready(observer, request.getMerchantId())) return;
                var item = items.get(UUID.fromString(request.getVariantId()));
                var status = resolution != null ? resolution : item != null && item.merchant().equals(IDENTITY.get().merchantId())
                        ? ResolutionStatus.MATCHED : ResolutionStatus.NOT_FOUND;
                var response = ResolveProductVariantResponse.newBuilder().setStatus(status);
                if (status == ResolutionStatus.MATCHED) response.setTotalMatches(1).addCandidates(Variant.newBuilder().setVariantId(request.getVariantId()));
                observer.onNext(response.build()); observer.onCompleted();
            }
            @Override public void getInventory(GetInventoryRequest request, StreamObserver<GetInventoryResponse> observer) {
                inventories.incrementAndGet();
                if (!ready(observer, request.getMerchantId())) return;
                var item = items.get(UUID.fromString(request.getVariantId()));
                if (item == null || !item.merchant().equals(IDENTITY.get().merchantId())) {
                    observer.onError(Status.NOT_FOUND.asRuntimeException()); return;
                }
                observer.onNext(GetInventoryResponse.newBuilder().setVariantId(request.getVariantId()).setQuantity(item.quantity())
                        .setAvailabilityStatus(corruptStock ? AvailabilityStatus.AVAILABILITY_STATUS_UNSPECIFIED
                                : item.quantity() == 0 ? AvailabilityStatus.UNAVAILABLE : AvailabilityStatus.AVAILABLE)
                        .setUpdatedAt(java.time.Instant.now().toString()).build());
                observer.onCompleted();
            }
        };
        try { server = NettyServerBuilder.forPort(0).addService(ServerInterceptors.intercept(handler, auth)).build().start(); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    private <T> boolean ready(StreamObserver<T> observer, String merchant) {
        bounded = Context.current().getDeadline() != null;
        if (!merchant.equals(IDENTITY.get().merchantId().toString())) {
            observer.onError(Status.NOT_FOUND.asRuntimeException()); return false;
        }
        if (delayMillis > 0) {
            try { Thread.sleep(delayMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (failure != null) { observer.onError(failure.asRuntimeException()); return false; }
        return true;
    }
    public int port() { return server.getPort(); }
    public void reset() {
        items.clear(); failure = null; resolution = null; corruptStock = false; delayMillis = 0;
        resolutions.set(0); inventories.set(0); bounded = false;
    }
    @Override public void close() throws InterruptedException { server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }
}
