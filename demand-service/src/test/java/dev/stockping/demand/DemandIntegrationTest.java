package dev.stockping.demand;

import com.jayway.jsonpath.JsonPath;
import dev.stockping.demand.domain.*;
import dev.stockping.demand.repository.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemandIntegrationTest extends PostgresTest {
    static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111"), B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID VARIANT = UUID.randomUUID(), OTHER_VARIANT = UUID.randomUUID(), B_VARIANT = UUID.randomUUID();
    static final String PASSWORD = "demo-only-password";
    static final String HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MerchantRepository merchants;
    @Autowired UserRepository users;
    @Autowired CustomerRepository customers;
    @Autowired ConsentRecordRepository consents;
    @Autowired WaitlistRepository waitlists;
    @Autowired DemandAuditRepository audits;
    @Autowired PasswordEncoder passwords;
    HttpClient http;
    String tokenA, tokenB;

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE demand_audit, waitlist_requests, consent_records, customers, users, merchants");
        merchants.saveAndFlush(new Merchant(A, "Shop A", "Asia/Colombo"));
        merchants.saveAndFlush(new Merchant(B, "Shop B", "UTC"));
        users.saveAndFlush(new User(A, "admin@a.test", HASH));
        users.saveAndFlush(new User(B, "admin@b.test", HASH));
        CATALOG.reset();
        CATALOG.items.put(VARIANT, new TestCatalog.Item(A, 0));
        CATALOG.items.put(OTHER_VARIANT, new TestCatalog.Item(A, 0));
        CATALOG.items.put(B_VARIANT, new TestCatalog.Item(B, 0));
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        tokenA = TestTokens.token(A); tokenB = TestTokens.token(B);
    }
    @AfterEach void close() { http.close(); }

    HttpResponse<String> call(String token, String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> create(String token, UUID variant, String phone, boolean consent) throws Exception {
        return call(token, "POST", "/api/waitlists", """
                {"customerPhone":"%s","displayName":"Demo Customer","language":"en","variantId":"%s","consent":%s}
                """.formatted(phone, variant, consent));
    }
    String created(String token, UUID variant, String phone) throws Exception {
        var response = create(token, variant, phone, true);
        assertEquals(201, response.statusCode(), response.body());
        return response.body();
    }
    void error(int status, String code, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertEquals(status, JsonPath.<Integer>read(response.body(), "$.status"));
        assertFalse(response.body().contains("stackTrace"));
        assertFalse(response.body().contains("passwordHash"));
    }
    String customer(String waitlist) { return JsonPath.read(waitlist, "$.customerId"); }
    String id(String waitlist) { return JsonPath.read(waitlist, "$.id"); }
    long active(String token) throws Exception { return JsonPath.<Number>read(call(token, "GET", "/api/analytics/demand", null).body(), "$.totalActive").longValue(); }

    @Test void validLoginHashesPasswordsAndIssuesUsableIdentity() throws Exception {
        var response = call(null, "POST", "/api/auth/login", "{\"email\":\"ADMIN@A.TEST\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(A.toString(), JsonPath.read(response.body(), "$.merchantId"));
        assertEquals("MERCHANT_ADMIN", JsonPath.read(response.body(), "$.role"));
        String token = JsonPath.read(response.body(), "$.accessToken");
        assertEquals(200, call(token, "GET", "/api/waitlists", null).statusCode());
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        var stored = users.findByEmail("admin@a.test").orElseThrow();
        assertNotEquals(PASSWORD, stored.getPasswordHash());
        assertTrue(stored.getPasswordHash().startsWith("$2a$12$"));
        assertTrue(passwords.matches(PASSWORD, stored.getPasswordHash()));
        assertFalse(response.body().contains(PASSWORD)); assertFalse(response.body().contains(HASH));
        Instant expiry = Instant.parse(JsonPath.read(response.body(), "$.expiresAt"));
        assertTrue(Duration.between(Instant.now(), expiry).toSeconds() > 1700);
    }
    @Test void invalidPasswordAndUnknownUserHaveSameSafeResponse() throws Exception {
        var bad = call(null, "POST", "/api/auth/login", "{\"email\":\"admin@a.test\",\"password\":\"wrong\"}");
        var unknown = call(null, "POST", "/api/auth/login", "{\"email\":\"unknown@a.test\",\"password\":\"wrong\"}");
        error(401, "INVALID_CREDENTIALS", bad); error(401, "INVALID_CREDENTIALS", unknown);
        assertEquals(bad.body(), unknown.body());
    }
    @Test void inactiveUsersAndMerchantsCannotLogIn() throws Exception {
        String login = "{\"email\":\"admin@a.test\",\"password\":\"" + PASSWORD + "\"}";
        jdbc.update("update users set status='INACTIVE' where merchant_id=?", A);
        error(401, "INVALID_CREDENTIALS", call(null, "POST", "/api/auth/login", login));
        jdbc.update("update users set status='ACTIVE' where merchant_id=?", A);
        jdbc.update("update merchants set status='INACTIVE' where id=?", A);
        error(401, "INVALID_CREDENTIALS", call(null, "POST", "/api/auth/login", login));
    }
    @Test void anonymousInvalidExpiredAndWrongRoleAreRejected() throws Exception {
        error(401, "UNAUTHENTICATED", call(null, "GET", "/api/waitlists", null));
        error(401, "INVALID_TOKEN", call("garbage", "GET", "/api/waitlists", null));
        error(401, "INVALID_TOKEN", call(TestTokens.token(A, "MERCHANT_ADMIN", -30), "GET", "/api/waitlists", null));
        error(403, "FORBIDDEN", call(TestTokens.token(A, "OTHER", 100), "GET", "/api/waitlists", null));
        assertEquals(200, call(tokenA, "GET", "/api/waitlists", null).statusCode());
    }
    @Test void statelessLogoutAcknowledgesAndTokenExpiresNaturally() throws Exception {
        assertEquals(204, call(tokenA, "POST", "/api/auth/logout", null).statusCode());
        assertEquals(200, call(tokenA, "GET", "/api/waitlists", null).statusCode());
        error(401, "UNAUTHENTICATED", call(null, "GET", "/api/waitlists", null));
    }
    @Test void cleanMigrationAndDomainPersistence() throws Exception {
        assertEquals(1, jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class));
        String item = created(tokenA, VARIANT, "+94 (77) 123-4567");
        assertEquals("WAITING", JsonPath.read(item, "$.status"));
        assertEquals("+94771234567", JsonPath.read(item, "$.customerPhone"));
        assertTrue(JsonPath.<Boolean>read(item, "$.eligible"));
        var stored = customers.findByIdAndMerchantId(UUID.fromString(customer(item)), A).orElseThrow();
        assertEquals("Demo Customer", stored.getDisplayName()); assertEquals("en", stored.getLanguage());
        assertEquals(1, waitlists.count()); assertEquals(1, consents.count()); assertEquals(2, audits.count());
        assertEquals(2, merchants.count()); assertEquals(2, users.count());
        assertEquals(1, CATALOG.resolutions.get()); assertEquals(1, CATALOG.inventories.get()); assertTrue(CATALOG.bounded);
        assertEquals(1, active(tokenA));
    }
    @Test void duplicateIsPreventedButAnotherVariantIsAllowed() throws Exception {
        created(tokenA, VARIANT, "+94771234567");
        error(409, "DUPLICATE_ACTIVE_WAITLIST", create(tokenA, VARIANT, "+94 77 123 4567", true));
        created(tokenA, OTHER_VARIANT, "+94771234567");
        assertEquals(1, customers.count()); assertEquals(2, waitlists.count()); assertEquals(1, consents.count());
    }
    @Test void samePhoneIsIndependentAcrossMerchants() throws Exception {
        created(tokenA, VARIANT, "+94771234567"); created(tokenB, B_VARIANT, "+94771234567");
        assertEquals(2, customers.count()); assertEquals(1, active(tokenA)); assertEquals(1, active(tokenB));
        var first = customers.findByMerchantIdAndWhatsappPhone(A, "+94771234567").orElseThrow();
        var second = customers.findByMerchantIdAndWhatsappPhone(B, "+94771234567").orElseThrow();
        assertNotEquals(first.getId(), second.getId());
        assertTrue(customers.findByIdAndMerchantId(first.getId(), B).isEmpty());
    }
    @Test void missingConsentMalformedPhoneAndUnknownJsonFieldsRollBack() throws Exception {
        error(409, "CONSENT_REQUIRED", create(tokenA, VARIANT, "+94771234567", false));
        error(400, "INVALID_REQUEST", create(tokenA, VARIANT, "0771234567", true));
        error(400, "INVALID_REQUEST", call(tokenA, "POST", "/api/waitlists", """
                {"customerPhone":"+94771234567","variantId":"%s","consent":true,"merchantId":"%s"}
                """.formatted(VARIANT, B)));
        assertEquals(0, customers.count()); assertEquals(0, consents.count()); assertEquals(0, waitlists.count());
    }
    @Test void availableAndMissingVariantsCreateNoDemandOrConsent() throws Exception {
        CATALOG.items.put(VARIANT, new TestCatalog.Item(A, 5));
        error(409, "ITEM_AVAILABLE", create(tokenA, VARIANT, "+94771234567", true));
        error(404, "VARIANT_NOT_FOUND", create(tokenA, UUID.randomUUID(), "+94771234567", true));
        assertEquals(0, customers.count()); assertEquals(0, consents.count()); assertEquals(0, audits.count());
    }
    @Test void catalogOutageAndTimeoutLeaveNoPartialRecords() throws Exception {
        CATALOG.failure = io.grpc.Status.UNAVAILABLE;
        error(503, "CATALOG_UNAVAILABLE", create(tokenA, VARIANT, "+94771234567", true));
        CATALOG.failure = null; CATALOG.delayMillis = 2000;
        error(504, "CATALOG_TIMEOUT", create(tokenA, VARIANT, "+94771234567", true));
        assertEquals(0, waitlists.count()); assertEquals(0, customers.count()); assertEquals(0, consents.count()); assertEquals(0, audits.count());
    }
    @Test void optOutPreservesHistoryAndSuppressesEveryActiveWaitlist() throws Exception {
        String first = created(tokenA, VARIANT, "+94771234567");
        created(tokenA, OTHER_VARIANT, "+94771234567");
        var result = call(tokenA, "POST", "/api/customers/" + customer(first) + "/opt-out", null);
        assertEquals(200, result.statusCode(), result.body());
        assertEquals(2, JsonPath.<Integer>read(result.body(), "$.updatedWaitlists"));
        assertEquals(0, active(tokenA));
        String detail = call(tokenA, "GET", "/api/waitlists/" + id(first), null).body();
        assertEquals("OPTED_OUT", JsonPath.read(detail, "$.status")); assertFalse(JsonPath.<Boolean>read(detail, "$.eligible"));
        var history = consents.findByMerchantIdAndCustomerIdOrderBySequenceAsc(A, UUID.fromString(customer(first)));
        assertEquals(List.of(ConsentStatus.OPT_IN, ConsentStatus.OPT_OUT), history.stream().map(ConsentRecord::getStatus).toList());
        error(409, "CUSTOMER_OPTED_OUT", create(tokenA, VARIANT, "+94771234567", true));
        assertEquals(2, consents.count());
    }
    @Test void explicitRenewedConsentPermitsNewRequestAndKeepsOldOneOptedOut() throws Exception {
        String first = created(tokenA, VARIANT, "+94771234567");
        call(tokenA, "POST", "/api/customers/" + customer(first) + "/opt-out", null);
        var renewed = call(tokenA, "POST", "/api/customers/" + customer(first) + "/opt-in", null);
        assertEquals(200, renewed.statusCode());
        var second = create(tokenA, VARIANT, "+94771234567", false);
        assertEquals(201, second.statusCode(), second.body());
        assertNotEquals(id(first), id(second.body())); assertEquals(3, consents.count()); assertEquals(1, active(tokenA));
        assertEquals(WaitlistStatus.OPTED_OUT, waitlists.findById(UUID.fromString(id(first))).orElseThrow().getStatus());
    }
    @Test void merchantCannotReadOrChangeAnotherMerchantsResourcesOrSpoofContext() throws Exception {
        String ownedByB = created(tokenB, B_VARIANT, "+94771234567");
        error(404, "NOT_FOUND", call(tokenA, "GET", "/api/waitlists/" + id(ownedByB), null));
        error(404, "NOT_FOUND", call(tokenA, "POST", "/api/customers/" + customer(ownedByB) + "/opt-out", null));
        error(404, "NOT_FOUND", call(tokenA, "POST", "/api/customers/" + customer(ownedByB) + "/opt-in", null));
        error(404, "VARIANT_NOT_FOUND", create(tokenA, B_VARIANT, "+94771234567", true));
        String list = call(tokenA, "GET", "/api/waitlists?merchantId=" + B + "&customerId=" + customer(ownedByB), null).body();
        assertEquals(0, JsonPath.<Integer>read(list, "$.totalElements")); assertEquals(1, active(tokenB));
        assertEquals(1, consents.count()); assertEquals(1, customers.count());
    }
    @Test void filtersPaginationDetailAndCountsAreMerchantScoped() throws Exception {
        String first = created(tokenA, VARIANT, "+94771234567"); created(tokenA, VARIANT, "+94779876543");
        created(tokenA, OTHER_VARIANT, "+94771234567"); created(tokenB, B_VARIANT, "+94771234567");
        String page = call(tokenA, "GET", "/api/waitlists?status=WAITING&variantId=" + VARIANT + "&size=1", null).body();
        assertEquals(2, JsonPath.<Integer>read(page, "$.totalElements")); assertEquals(1, JsonPath.<List<?>>read(page, "$.items").size());
        assertEquals(2, JsonPath.<Integer>read(call(tokenA, "GET", "/api/waitlists?customerId=" + customer(first), null).body(), "$.totalElements"));
        assertEquals(0, JsonPath.<Integer>read(call(tokenA, "GET", "/api/waitlists?from=2999-01-01T00:00:00Z", null).body(), "$.totalElements"));
        assertEquals(3, active(tokenA));
        String analytics = call(tokenA, "GET", "/api/analytics/demand", null).body();
        assertEquals(3, JsonPath.<Integer>read(analytics, "$.totalWaiting"));
        assertEquals(2, JsonPath.<List<?>>read(analytics, "$.variants").size());
        error(400, "INVALID_REQUEST", call(tokenA, "GET", "/api/waitlists?size=101", null));
        error(400, "INVALID_REQUEST", call(tokenA, "GET", "/api/waitlists?from=2030-01-01T00:00:00Z&to=2020-01-01T00:00:00Z", null));
    }
    @Test void concurrentDuplicateCreatesExactlyOneCustomerConsentAndWaitlist() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> create(tokenA, VARIANT, "+94771234567", true));
            var second = pool.submit(() -> create(tokenA, VARIANT, "+94 77 123 4567", true));
            assertEquals(Set.of(201, 409), Set.of(first.get(15, TimeUnit.SECONDS).statusCode(), second.get(15, TimeUnit.SECONDS).statusCode()));
        }
        assertEquals(1, customers.count()); assertEquals(1, consents.count()); assertEquals(1, waitlists.count()); assertEquals(2, audits.count());
    }
    @Test void concurrentOptOutAndCreateCannotLeaveActiveEligibility() throws Exception {
        String first = created(tokenA, VARIANT, "+94771234567");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var create = pool.submit(() -> create(tokenA, OTHER_VARIANT, "+94771234567", true));
            var out = pool.submit(() -> call(tokenA, "POST", "/api/customers/" + customer(first) + "/opt-out", null));
            assertTrue(Set.of(201, 409).contains(create.get(15, TimeUnit.SECONDS).statusCode()));
            assertEquals(200, out.get(15, TimeUnit.SECONDS).statusCode());
        }
        assertEquals(0, active(tokenA));
        assertEquals(ConsentStatus.OPT_OUT, consents.findFirstByMerchantIdAndCustomerIdOrderBySequenceDesc(A, UUID.fromString(customer(first))).orElseThrow().getStatus());
    }
    @Test void databaseEnforcesCustomerAndActiveWaitlistUniquenessAndTenantForeignKeys() throws Exception {
        String item = created(tokenA, VARIANT, "+94771234567"); UUID customer = UUID.fromString(customer(item));
        assertThrows(DataIntegrityViolationException.class, () -> customers.saveAndFlush(new Customer(A, "+94771234567", null, null)));
        assertThrows(DataIntegrityViolationException.class, () -> waitlists.saveAndFlush(new WaitlistRequest(A, customer, VARIANT)));
        assertThrows(DataIntegrityViolationException.class, () -> waitlists.saveAndFlush(new WaitlistRequest(B, customer, B_VARIANT)));
        assertThrows(DataIntegrityViolationException.class, () -> consents.saveAndFlush(new ConsentRecord(B, customer, ConsentStatus.OPT_OUT)));
        assertThrows(DataIntegrityViolationException.class, () -> users.saveAndFlush(new User(B, "ADMIN@A.TEST", HASH)));
        jdbc.update("update waitlist_requests set status='NOTIFICATION_QUEUED' where id=?", UUID.fromString(id(item)));
        assertThrows(DataIntegrityViolationException.class, () -> waitlists.saveAndFlush(new WaitlistRequest(A, customer, VARIANT)));
        assertEquals(1, active(tokenA));
        var optOut = call(tokenA, "POST", "/api/customers/" + customer + "/opt-out", null);
        assertEquals(1, JsonPath.<Integer>read(optOut.body(), "$.updatedWaitlists"));
    }
    @Test void auditFailureRollsBackCustomerConsentAndWaitlist() throws Exception {
        jdbc.execute("CREATE FUNCTION reject_demand_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_demand_audit BEFORE INSERT ON demand_audit FOR EACH ROW EXECUTE FUNCTION reject_demand_audit()");
        try {
            error(500, "INTERNAL_ERROR", create(tokenA, VARIANT, "+94771234567", true));
            assertEquals(0, customers.count()); assertEquals(0, consents.count()); assertEquals(0, waitlists.count());
        } finally {
            jdbc.execute("DROP TRIGGER reject_demand_audit ON demand_audit"); jdbc.execute("DROP FUNCTION reject_demand_audit()");
        }
    }
    @Test void malformedRequestsHaveConsistentErrors() throws Exception {
        error(400, "INVALID_REQUEST", call(tokenA, "POST", "/api/waitlists", "{"));
        error(400, "INVALID_REQUEST", call(tokenA, "GET", "/api/waitlists?status=BOGUS", null));
        error(400, "INVALID_REQUEST", call(tokenA, "GET", "/api/waitlists/not-a-uuid", null));
        error(404, "NOT_FOUND", call(tokenA, "GET", "/api/waitlists/" + UUID.randomUUID(), null));
    }
    @Test void failedOptOutAuditPreservesConsentAndActiveWaitlist() throws Exception {
        String item = created(tokenA, VARIANT, "+94771234567");
        jdbc.execute("CREATE FUNCTION reject_opt_out_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_opt_out_audit BEFORE INSERT ON demand_audit FOR EACH ROW EXECUTE FUNCTION reject_opt_out_audit()");
        try {
            error(500, "INTERNAL_ERROR", call(tokenA, "POST", "/api/customers/" + customer(item) + "/opt-out", null));
            assertEquals(1, consents.count()); assertEquals(1, active(tokenA));
            assertEquals(ConsentStatus.OPT_IN, consents.findFirstByMerchantIdAndCustomerIdOrderBySequenceDesc(A, UUID.fromString(customer(item))).orElseThrow().getStatus());
        } finally {
            jdbc.execute("DROP TRIGGER reject_opt_out_audit ON demand_audit"); jdbc.execute("DROP FUNCTION reject_opt_out_audit()");
        }
    }
    @Test void browserCannotSupplyAuthoritativeStock() throws Exception {
        error(400, "INVALID_REQUEST", call(tokenA, "POST", "/api/waitlists", """
                {"customerPhone":"+94771234567","variantId":"%s","consent":true,"quantity":0}
                """.formatted(VARIANT)));
        assertEquals(0, waitlists.count()); assertEquals(0, CATALOG.resolutions.get());
    }
}
