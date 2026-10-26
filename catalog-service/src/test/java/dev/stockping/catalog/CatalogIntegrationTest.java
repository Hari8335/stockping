package dev.stockping.catalog;

import com.jayway.jsonpath.JsonPath;
import dev.stockping.catalog.domain.*;
import dev.stockping.catalog.grpc.CatalogGrpcServer;
import dev.stockping.catalog.repository.*;
import dev.stockping.contracts.catalog.v1.*;
import io.grpc.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogIntegrationTest extends PostgresTest {
    static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductRepository products;
    @Autowired ProductVariantRepository variants;
    @Autowired InventoryRepository inventories;
    @Autowired StockEventRepository events;
    @Autowired CatalogGrpcServer grpcServer;
    HttpClient http;
    ManagedChannel channel;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE stock_events, inventory, product_variants, products");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        channel = ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort()).usePlaintext().build();
    }
    @AfterEach void close() throws Exception {
        http.close(); channel.shutdownNow(); assertTrue(channel.awaitTermination(5,TimeUnit.SECONDS));
    }
    HttpResponse<String> call(String method, String path, String json) throws Exception {
        return callAs(TestTokens.token(A), method, path, json);
    }
    HttpResponse<String> callAs(String token, String method, String path, String json) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(15));
        builder.header("Content-Type","application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.method(method,json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    String createProduct() throws Exception {
        var response = call("POST","/api/products","""
                {"name":"Galaxy S25 Ultra","brand":"Samsung","category":"Phones"}
                """);
        assertEquals(201,response.statusCode(),response.body());
        assertTrue(response.headers().firstValue("Location").orElseThrow().startsWith("/api/products/"));
        return JsonPath.read(response.body(),"$.id");
    }
    String createVariant(String product, String sku, String storage, String color) throws Exception {
        var response = call("POST","/api/products/"+product+"/variants", """
                {"sku":"%s","attributes":{"storage":"%s","colour":"%s"},"price":100.50}
                """.formatted(sku,storage,color));
        assertEquals(201,response.statusCode(),response.body());
        assertEquals(color,JsonPath.read(response.body(),"$.attributes.color"));
        return JsonPath.read(response.body(),"$.id");
    }
    void assertError(int expected, HttpResponse<String> response) {
        assertEquals(expected,response.statusCode(),response.body());
        assertEquals(expected,JsonPath.<Integer>read(response.body(),"$.status"));
        assertNotNull(JsonPath.read(response.body(),"$.code"));
        assertFalse(response.body().contains("stackTrace"));
    }
    CatalogServiceGrpc.CatalogServiceBlockingStub client() {
        var metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + TestTokens.token(A));
        return CatalogServiceGrpc.newBlockingStub(channel)
                .withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(metadata)).withDeadlineAfter(10,TimeUnit.SECONDS);
    }
    ResolveProductVariantRequest resolve(String sku) {
        return ResolveProductVariantRequest.newBuilder().setMerchantId(A.toString()).setSku(sku).build();
    }
    @Test void productCrudAndFilters() throws Exception {
        String id = createProduct(); createVariant(id,"SKU-1","512GB","Black");
        var list = call("GET","/api/products?name=s25&brand=samsung&status=ACTIVE",null);
        assertEquals(200,list.statusCode()); assertEquals(1,JsonPath.<Integer>read(list.body(),"$.totalElements"));
        var detail = call("GET","/api/products/"+id,null);
        assertEquals("Galaxy S25 Ultra",JsonPath.read(detail.body(),"$.product.name"));
        assertEquals(1,JsonPath.<List<?>>read(detail.body(),"$.variants").size());
        var updated = call("PATCH","/api/products/"+id,"{\"name\":\"Renamed phone\",\"status\":\"INACTIVE\"}");
        assertEquals(200,updated.statusCode()); assertEquals("INACTIVE",JsonPath.read(updated.body(),"$.status"));
        assertEquals(0,JsonPath.<Integer>read(call("GET","/api/products?status=ACTIVE",null).body(),"$.totalElements"));
    }
    @Test void variantUpdateAndSkuUniqueness() throws Exception {
        String product = createProduct(); String first = createVariant(product,"ONE","512GB","Black");
        String second = createVariant(product,"TWO","256GB","Silver");
        assertError(409,call("POST","/api/products/"+product+"/variants","{\"sku\":\"ONE\",\"attributes\":{}}"));
        assertError(409,call("PATCH","/api/variants/"+second,"{\"sku\":\"ONE\"}"));
        var updated=call("PATCH","/api/variants/"+first,"{\"sku\":\"NEW\",\"attributes\":{\"storage\":\"1TB\"},\"price\":200.99,\"status\":\"INACTIVE\"}");
        assertEquals(200,updated.statusCode(),updated.body());
        assertEquals("NEW",JsonPath.read(updated.body(),"$.sku"));
        assertEquals("1TB",JsonPath.read(updated.body(),"$.attributes.storage"));
        assertEquals("INACTIVE",JsonPath.read(updated.body(),"$.status"));
    }
    @Test void inventoryChangesAreAtomicAndAudited() throws Exception {
        String variant = createVariant(createProduct(),"INV","512GB","Black");
        var initial = call("GET","/api/variants/"+variant+"/inventory",null);
        assertEquals(0,JsonPath.<Integer>read(initial.body(),"$.quantity"));
        assertEquals("UNAVAILABLE",JsonPath.read(initial.body(),"$.availabilityStatus"));
        int[] quantities={0,5,10,0,0}; boolean[] restocks={false,true,false,false,false};
        boolean[] changed={false,true,true,true,false};
        for(int i=0;i<quantities.length;i++) {
            var update=call("PATCH","/api/variants/"+variant+"/inventory","{\"quantity\":"+quantities[i]+"}");
            assertEquals(200,update.statusCode(),update.body());
            assertEquals(restocks[i],JsonPath.<Boolean>read(update.body(),"$.restockTransition"));
            assertEquals(changed[i],JsonPath.<Boolean>read(update.body(),"$.changed"));
        }
        var audit=events.findByVariantIdAndMerchantIdOrderByCreatedAtAsc(UUID.fromString(variant),A);
        assertEquals(3,audit.size());
        assertEquals(List.of(0,5,10),audit.stream().map(StockEvent::getPreviousQuantity).toList());
        assertEquals(List.of(5,10,0),audit.stream().map(StockEvent::getNewQuantity).toList());
        assertError(400,call("PATCH","/api/variants/"+variant+"/inventory","{\"quantity\":-1}"));
        assertEquals(3,events.count()); assertEquals(0,inventories.findByVariantIdAndMerchantId(UUID.fromString(variant),A).orElseThrow().getQuantity());
    }
    @Test void malformedAndInvalidInputsAreRejected() throws Exception {
        for (String json:List.of("{}","{\"name\":\" \"}","{\"name\":\"Phone\",\"brand\":\"X\",\"category\":\"Y\",\"status\":\"UNKNOWN\"}",
                "{\"name\":\"Phone\",\"brand\":\"X\",\"category\":\"Y\",\"status\":1}",
                "{\"name\":\"Phone\",\"brand\":\"X\",\"category\":\"Y\",\"merchantId\":\""+B+"\"}","{"))
            assertError(400,call("POST","/api/products",json));
        String product=createProduct();
        for(String json:List.of("{}","{\"sku\":\"  \",\"attributes\":{}}",
                "{\"sku\":\"X\",\"attributes\":{\"color\":\"\"}}", "{\"sku\":\"X\",\"attributes\":{\"color\":\"Black\",\"colour\":\"Black\"}}",
                "{\"sku\":\"X\",\"attributes\":{},\"price\":-1}", "{\"sku\":\"X\",\"attributes\":{},\"price\":1.234}"))
            assertError(400,call("POST","/api/products/"+product+"/variants",json));
        assertError(400,call("PATCH","/api/products/"+product,"{\"name\":\" \"}"));
        assertError(400,call("GET","/api/products?size=101",null));
        assertError(400,call("GET","/api/products?status=BOGUS",null));
        assertError(400,call("GET","/api/variants/search?unknown=x",null));
        assertError(400,call("GET","/api/products/not-a-uuid",null));
        String variant=createVariant(product,"VALID","512GB","Black");
        for(String body:List.of("{}","{\"quantity\":null}","{\"quantity\":1.5}","{\"quantity\":2147483648}","{\"quantity\":1,\"availabilityStatus\":\"AVAILABLE\"}"))
            assertError(400,call("PATCH","/api/variants/"+variant+"/inventory",body));
    }
    @Test void nonexistentResourcesAreHidden() throws Exception {
        String id=UUID.randomUUID().toString();
        assertError(404,call("GET","/api/products/"+id,null));
        assertError(404,call("PATCH","/api/products/"+id,"{\"name\":\"X\"}"));
        assertError(404,call("POST","/api/products/"+id+"/variants","{\"sku\":\"X\",\"attributes\":{}}"));
        assertError(404,call("PATCH","/api/variants/"+id,"{\"sku\":\"X\"}"));
        assertError(404,call("GET","/api/variants/"+id+"/inventory",null));
        assertError(404,call("PATCH","/api/variants/"+id+"/inventory","{\"quantity\":5}"));
    }
    ProductVariant seedOtherMerchant() {
        var product=products.saveAndFlush(new Product(B,"Other Merchant Phone","Samsung","Phones",CatalogStatus.ACTIVE));
        var variant=variants.saveAndFlush(new ProductVariant(product,"OTHER",Map.of("storage","512GB","color","Black"),null,CatalogStatus.ACTIVE));
        inventories.saveAndFlush(new Inventory(B,variant.getId())); return variant;
    }
    @Test void restEnforcesMerchantOwnershipForEveryOperation() throws Exception {
        var other=seedOtherMerchant(); String p=other.getProduct().getId().toString(), v=other.getId().toString();
        assertError(404,call("GET","/api/products/"+p+"?merchantId="+B,null));
        assertError(404,call("PATCH","/api/products/"+p,"{\"name\":\"Stolen\"}"));
        assertError(404,call("POST","/api/products/"+p+"/variants","{\"sku\":\"X\",\"attributes\":{}}"));
        assertError(404,call("PATCH","/api/variants/"+v,"{\"sku\":\"Stolen\"}"));
        assertError(404,call("GET","/api/variants/"+v+"/inventory",null));
        assertError(404,call("PATCH","/api/variants/"+v+"/inventory","{\"quantity\":7}"));
        assertEquals(0,JsonPath.<Integer>read(call("GET","/api/products",null).body(),"$.totalElements"));
        assertEquals("NOT_FOUND",JsonPath.read(call("GET","/api/variants/search?sku=OTHER",null).body(),"$.resolutionStatus"));
        assertEquals(0,events.count()); assertEquals("OTHER",variants.findByIdAndMerchantId(other.getId(),B).orElseThrow().getSku());
        createVariant(createProduct(),"OTHER","512GB","Black"); // Same SKU is valid in another tenant.
    }
    @Test void deterministicSearchDistinguishesAllOutcomes() throws Exception {
        String p=createProduct(); String first=createVariant(p,"BLACK-512","512GB","Black");
        createVariant(p,"SILVER-256","256GB","Silver");
        var matched=call("GET","/api/variants/search?productName=s25&brand=samsung&storage=512gb&color=black&status=ACTIVE",null);
        assertEquals("MATCHED",JsonPath.read(matched.body(),"$.resolutionStatus"));
        assertEquals(first,JsonPath.read(matched.body(),"$.candidates[0].id"));
        var ambiguous=call("GET","/api/variants/search?productName=s25&size=1",null);
        assertEquals("AMBIGUOUS",JsonPath.read(ambiguous.body(),"$.resolutionStatus"));
        assertEquals(2,JsonPath.<Integer>read(ambiguous.body(),"$.totalMatches"));
        assertEquals("NOT_FOUND",JsonPath.read(call("GET","/api/variants/search?sku=missing",null).body(),"$.resolutionStatus"));
        assertEquals("NOT_FOUND",JsonPath.read(call("GET","/api/variants/search?productName=%25",null).body(),"$.resolutionStatus"));
        assertEquals("MATCHED",JsonPath.read(call("GET","/api/variants/search?attribute.colour=black",null).body(),"$.resolutionStatus"));
    }
    @Test void grpcClientResolvesExactVariantAndReadsAuthoritativeStock() throws Exception {
        String v=createVariant(createProduct(),"GRPC","512GB","Black");
        call("PATCH","/api/variants/"+v+"/inventory","{\"quantity\":5}");
        var response=client().resolveProductVariant(resolve("GRPC").toBuilder().setProductName("s25").setBrand("Samsung")
                .putAttributes("storage","512gb").putAttributes("colour","black").build());
        assertEquals(ResolutionStatus.MATCHED,response.getStatus()); assertEquals(v,response.getCandidates(0).getVariantId());
        var stock=client().getInventory(GetInventoryRequest.newBuilder().setMerchantId(A.toString()).setVariantId(v).build());
        assertEquals(5,stock.getQuantity()); assertEquals(AvailabilityStatus.AVAILABLE,stock.getAvailabilityStatus());
        assertFalse(stock.getUpdatedAt().isBlank());
    }
    @Test void grpcNoMatchAmbiguityAndInactiveRecords() throws Exception {
        String p=createProduct(); String v=createVariant(p,"G1","512GB","Black"); createVariant(p,"G2","256GB","Silver");
        assertEquals(ResolutionStatus.NOT_FOUND,client().resolveProductVariant(resolve("MISSING")).getStatus());
        var broad=ResolveProductVariantRequest.newBuilder().setMerchantId(A.toString()).setBrand("Samsung").build();
        assertEquals(ResolutionStatus.AMBIGUOUS,client().resolveProductVariant(broad).getStatus());
        call("PATCH","/api/variants/"+v,"{\"status\":\"INACTIVE\"}");
        assertEquals(ResolutionStatus.NOT_FOUND,client().resolveProductVariant(resolve("G1")).getStatus());
        call("PATCH","/api/products/"+p,"{\"status\":\"INACTIVE\"}");
        assertEquals(ResolutionStatus.NOT_FOUND,client().resolveProductVariant(broad).getStatus());
    }
    @Test void grpcInvalidRequestsAndTenantHidingHaveExplicitStatuses() {
        var other=seedOtherMerchant();
        assertGrpc(Status.Code.INVALID_ARGUMENT,()->client().resolveProductVariant(ResolveProductVariantRequest.getDefaultInstance()));
        assertGrpc(Status.Code.INVALID_ARGUMENT,()->client().resolveProductVariant(ResolveProductVariantRequest.newBuilder().setMerchantId(A.toString()).build()));
        assertGrpc(Status.Code.INVALID_ARGUMENT,()->client().getInventory(GetInventoryRequest.newBuilder().setMerchantId(A.toString()).setVariantId("bad").build()));
        assertGrpc(Status.Code.NOT_FOUND,()->client().getInventory(GetInventoryRequest.newBuilder().setMerchantId(A.toString()).setVariantId(other.getId().toString()).build()));
        assertGrpc(Status.Code.NOT_FOUND,()->client().getInventory(GetInventoryRequest.newBuilder().setMerchantId(B.toString()).setVariantId(other.getId().toString()).build()));
        assertGrpc(Status.Code.NOT_FOUND,()->client().resolveProductVariant(resolve("OTHER").toBuilder().setMerchantId(B.toString()).build()));
        assertEquals(ResolutionStatus.NOT_FOUND,client().resolveProductVariant(resolve("OTHER")).getStatus());
        assertGrpc(Status.Code.NOT_FOUND,()->client().getInventory(GetInventoryRequest.newBuilder().setMerchantId(A.toString()).setVariantId(UUID.randomUUID().toString()).build()));
    }
    @Test void grpcClientDeadlineIsEnforced() {
        assertGrpc(Status.Code.DEADLINE_EXCEEDED,()->client().withDeadlineAfter(0,TimeUnit.NANOSECONDS).resolveProductVariant(resolve("ANY")));
    }
    static void assertGrpc(Status.Code expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected,assertThrows(StatusRuntimeException.class,action).getStatus().getCode());
    }
    @Test void failedAuditInsertRollsBackInventory() throws Exception {
        String v=createVariant(createProduct(),"ROLLBACK","512GB","Black");
        jdbc.execute("CREATE FUNCTION reject_test_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test audit failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_test_audit BEFORE INSERT ON stock_events FOR EACH ROW EXECUTE FUNCTION reject_test_audit()");
        try {
            assertError(500,call("PATCH","/api/variants/"+v+"/inventory","{\"quantity\":5}"));
            assertEquals(0,inventories.findByVariantIdAndMerchantId(UUID.fromString(v),A).orElseThrow().getQuantity());
            assertEquals(0,events.count());
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_audit ON stock_events");
            jdbc.execute("DROP FUNCTION reject_test_audit()");
        }
    }
    @Test void concurrentDuplicateSkuCreatesOneVariantAndInventory() throws Exception {
        String p=createProduct();
        String body="{\"sku\":\"RACE\",\"attributes\":{}}";
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->call("POST","/api/products/"+p+"/variants",body));
            var second=pool.submit(()->call("POST","/api/products/"+p+"/variants",body));
            assertEquals(Set.of(201,409),Set.of(first.get(20,TimeUnit.SECONDS).statusCode(),second.get(20,TimeUnit.SECONDS).statusCode()));
        }
        assertEquals(1,variants.count()); assertEquals(1,inventories.count());
    }
    @Test void concurrentInventoryUpdatesKeepAuditChain() throws Exception {
        String v=createVariant(createProduct(),"CONCURRENT","512GB","Black");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->call("PATCH","/api/variants/"+v+"/inventory","{\"quantity\":5}"));
            var second=pool.submit(()->call("PATCH","/api/variants/"+v+"/inventory","{\"quantity\":10}"));
            assertEquals(200,first.get(20,TimeUnit.SECONDS).statusCode()); assertEquals(200,second.get(20,TimeUnit.SECONDS).statusCode());
        }
        var audit=events.findByVariantIdAndMerchantIdOrderByCreatedAtAsc(UUID.fromString(v),A);
        assertEquals(2,audit.size()); assertEquals(0,audit.get(0).getPreviousQuantity());
        assertEquals(audit.get(0).getNewQuantity(),audit.get(1).getPreviousQuantity());
        assertEquals(audit.get(1).getNewQuantity(),inventories.findByVariantIdAndMerchantId(UUID.fromString(v),A).orElseThrow().getQuantity());
    }
    @Test void restRequiresValidMerchantAdministratorToken() throws Exception {
        assertError(401, callAs(null, "GET", "/api/products", null));
        assertError(401, callAs("invalid", "GET", "/api/products", null));
        assertError(401, callAs(TestTokens.token(A, "MERCHANT_ADMIN", -30), "GET", "/api/products", null));
        assertError(403, callAs(TestTokens.token(A, "OTHER", 300), "GET", "/api/products", null));
        assertEquals(200, call("GET", "/api/products", null).statusCode());
    }
    @Test void signedSecondTenantWorksWithoutChangingServerConfiguration() throws Exception {
        var other = seedOtherMerchant();
        var response = callAs(TestTokens.token(B), "GET", "/api/variants/" + other.getId() + "/inventory", null);
        assertEquals(200, response.statusCode(), response.body());
        var headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + TestTokens.token(B));
        var clientB = CatalogServiceGrpc.newBlockingStub(channel)
                .withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(headers)).withDeadlineAfter(5, TimeUnit.SECONDS);
        var exact = ResolveProductVariantRequest.newBuilder().setMerchantId(B.toString()).setVariantId(other.getId().toString()).build();
        assertEquals(ResolutionStatus.MATCHED, clientB.resolveProductVariant(exact).getStatus());
        assertEquals(ResolutionStatus.NOT_FOUND, client().resolveProductVariant(exact.toBuilder().setMerchantId(A.toString()).build()).getStatus());
        assertGrpc(Status.Code.NOT_FOUND, () -> client().resolveProductVariant(exact));
        assertEquals(0, clientB.getInventory(GetInventoryRequest.newBuilder().setMerchantId(B.toString()).setVariantId(other.getId().toString()).build()).getQuantity());
    }
    @Test void grpcRejectsAnonymousInvalidExpiredAndWrongRole() {
        var anonymous = CatalogServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
        assertGrpc(Status.Code.UNAUTHENTICATED, () -> anonymous.resolveProductVariant(resolve("ANY")));
        for (String token : List.of("invalid", TestTokens.token(A, "MERCHANT_ADMIN", -30))) {
            var headers = new Metadata();
            headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
            var invalid = anonymous.withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(headers));
            assertGrpc(Status.Code.UNAUTHENTICATED, () -> invalid.resolveProductVariant(resolve("ANY")));
        }
        var headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + TestTokens.token(A, "OTHER", 300));
        var forbidden = anonymous.withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(headers));
        assertGrpc(Status.Code.PERMISSION_DENIED, () -> forbidden.resolveProductVariant(resolve("ANY")));
    }
    @Test void exactResolutionRejectsInactiveAndMixedCriteria() throws Exception {
        String variant = createVariant(createProduct(), "EXACT", "512GB", "Black");
        var exact = ResolveProductVariantRequest.newBuilder().setMerchantId(A.toString()).setVariantId(variant).build();
        assertEquals(ResolutionStatus.MATCHED, client().resolveProductVariant(exact).getStatus());
        assertGrpc(Status.Code.INVALID_ARGUMENT, () -> client().resolveProductVariant(exact.toBuilder().setSku("EXACT").build()));
        call("PATCH", "/api/variants/" + variant, "{\"status\":\"INACTIVE\"}");
        assertEquals(ResolutionStatus.NOT_FOUND, client().resolveProductVariant(exact).getStatus());
    }

}
