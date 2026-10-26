package dev.stockping.demand;

import dev.stockping.contracts.catalog.v1.ResolutionStatus;
import dev.stockping.demand.exception.DemandException;
import dev.stockping.demand.grpc.CatalogClient;
import dev.stockping.security.Identity;
import io.grpc.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class CatalogClientTest {
    TestCatalog server;
    ManagedChannel channel;
    CatalogClient client;
    UUID merchant = UUID.randomUUID(), variant = UUID.randomUUID();
    Identity identity;
    @BeforeEach void setup() {
        server = new TestCatalog();
        channel = ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
        client = new CatalogClient(channel, 1000);
        identity = new Identity(UUID.randomUUID(), merchant, "MERCHANT_ADMIN", TestTokens.token(merchant));
        server.items.put(variant, new TestCatalog.Item(merchant, 0));
    }
    @AfterEach void close() throws Exception {
        channel.shutdownNow(); assertTrue(channel.awaitTermination(5, TimeUnit.SECONDS)); server.close();
    }
    void error(int status, String code) {
        var failure = assertThrows(DemandException.class, () -> client.verifyUnavailable(identity, variant));
        assertEquals(status, failure.status()); assertEquals(code, failure.code());
    }
    @Test void resolvesExactVariantAndReadsZeroStockWithDeadline() {
        client.verifyUnavailable(identity, variant);
        assertEquals(1, server.resolutions.get()); assertEquals(1, server.inventories.get()); assertTrue(server.bounded);
    }
    @Test void availableInventoryCannotBeWaitlisted() {
        server.items.put(variant, new TestCatalog.Item(merchant, 3)); error(409, "ITEM_AVAILABLE");
    }
    @Test void anotherMerchantsVariantIsHidden() {
        server.items.put(variant, new TestCatalog.Item(UUID.randomUUID(), 0)); error(404, "VARIANT_NOT_FOUND");
    }
    @Test void ambiguousResolutionRequiresClarification() { server.resolution = ResolutionStatus.AMBIGUOUS; error(409, "AMBIGUOUS_VARIANT"); }
    @Test void missingVariantNeverRequestsInventory() {
        server.items.clear(); error(404, "VARIANT_NOT_FOUND"); assertEquals(0, server.inventories.get());
    }
    @Test void corruptAuthoritativeResponseIsRejected() { server.corruptStock = true; error(502, "CATALOG_INVALID_RESPONSE"); }
    @Test void unknownResolutionIsRejected() { server.resolution = ResolutionStatus.RESOLUTION_STATUS_UNSPECIFIED; error(502, "CATALOG_INVALID_RESPONSE"); }
    @ParameterizedTest
    @CsvSource({"UNAVAILABLE,503,CATALOG_UNAVAILABLE", "DEADLINE_EXCEEDED,504,CATALOG_TIMEOUT", "INVALID_ARGUMENT,400,CATALOG_INVALID_REQUEST",
            "INTERNAL,502,CATALOG_ERROR", "NOT_FOUND,404,VARIANT_NOT_FOUND", "UNAUTHENTICATED,401,INVALID_TOKEN", "PERMISSION_DENIED,403,FORBIDDEN"})
    void mapsGrpcFailures(String grpc, int status, String code) { server.failure = Status.fromCode(Status.Code.valueOf(grpc)); error(status, code); }
    @Test void realDeadlineBoundsSlowServer() {
        server.delayMillis = 2000;
        long start = System.nanoTime(); error(504, "CATALOG_TIMEOUT");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1800);
    }
    @Test void stoppedServerIsUnavailable() throws Exception { server.close(); error(503, "CATALOG_UNAVAILABLE"); }
}
