package dev.stockping.demand;

import dev.stockping.contracts.events.StockAvailable;
import dev.stockping.contracts.demand.v1.*;
import dev.stockping.demand.domain.*;
import dev.stockping.demand.grpc.MessageCatalogClient;
import dev.stockping.demand.messaging.NotificationService;
import dev.stockping.demand.service.ConsentService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationIntegrationTest extends PostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationService service;
    @Autowired ConsentService consent;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @MockitoBean MessageCatalogClient catalog;
    UUID merchant, other, variant, product;
    @BeforeEach void setup() {
        merchant=UUID.randomUUID(); other=UUID.randomUUID(); variant=UUID.randomUUID(); product=UUID.randomUUID();
        for(UUID m:List.of(merchant,other)) jdbc.update("INSERT INTO merchants(id,name,timezone,status,created_at,updated_at) VALUES(?,'Notification test','UTC','ACTIVE',now(),now())",m);
        when(catalog.notificationStock(eq(merchant),eq(variant),anyString())).thenReturn(stock(true));
    }
    Optional<MessageCatalogClient.NotificationStock> stock(boolean available) {return Optional.of(new MessageCatalogClient.NotificationStock(product.toString(),"Product","SKU","color: black",available));}
    UUID waiter(UUID tenant,String status,String consentStatus) {
        UUID customer=UUID.randomUUID(),waitlist=UUID.randomUUID();
        jdbc.update("INSERT INTO customers(id,merchant_id,whatsapp_phone,status,created_at,updated_at) VALUES(?,?,'+9477'||lpad((floor(random()*10000000))::text,7,'0'),'ACTIVE',now(),now())",customer,tenant);
        jdbc.update("INSERT INTO consent_records(id,merchant_id,customer_id,type,status,source,captured_at) VALUES(?,?,?,'RESTOCK_NOTIFICATIONS',?,'MANUAL_API',now())",UUID.randomUUID(),tenant,customer,consentStatus);
        jdbc.update("INSERT INTO waitlist_requests(id,merchant_id,customer_id,variant_id,status,created_at,updated_at) VALUES(?,?,?,?,?,now()-interval '1 minute',now())",waitlist,tenant,customer,variant,status);
        return customer;
    }
    StockAvailable event() {return new StockAvailable(UUID.randomUUID().toString(),StockAvailable.TYPE,1,Instant.now().toString(),merchant.toString(),product.toString(),variant.toString(),"SKU",0,5,UUID.randomUUID().toString());}
    int jobs(){return jdbc.queryForObject("SELECT count(*) FROM notification_jobs WHERE merchant_id=?",Integer.class,merchant);}
    AuthorizeNotificationRequest authorization(){var row=jdbc.queryForMap("SELECT * FROM notification_jobs WHERE merchant_id=?",merchant);return AuthorizeNotificationRequest.newBuilder().setMerchantId(merchant.toString()).setNotificationId(row.get("id").toString()).setAvailabilityEventId(row.get("availability_event_id").toString()).setCorrelationId(row.get("correlation_id").toString()).build();}
    @Test void multipleEligibleWaitersDeduplicateConcurrentEventsAndIsolateTenants() throws Exception {
        waiter(merchant,"WAITING","OPT_IN");waiter(merchant,"WAITING","OPT_IN");waiter(merchant,"WAITING","OPT_OUT");waiter(other,"WAITING","OPT_IN");
        var event=event();
        try(var pool=Executors.newFixedThreadPool(4)) {var tasks=new ArrayList<Future<?>>();for(int i=0;i<4;i++) tasks.add(pool.submit(()->service.process(event)));for(var task:tasks)task.get(10,TimeUnit.SECONDS);}
        assertEquals(2,jobs());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM event_outbox WHERE id IN(SELECT id FROM notification_jobs WHERE merchant_id=?)",Integer.class,merchant));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM notification_jobs WHERE merchant_id=?",Integer.class,other));
    }
    @ParameterizedTest @ValueSource(strings={"NOTIFIED","FULFILLED","EXPIRED","CANCELLED","OPTED_OUT","NOTIFICATION_QUEUED"})
    void inactiveWaitlistsNeverQueue(String status){waiter(merchant,status,"OPT_IN");service.process(event());assertEquals(0,jobs());}
    @Test void noWaiterOrInactiveAccountCreatesNoJob(){service.process(event());assertEquals(0,jobs());UUID c=waiter(merchant,"WAITING","OPT_IN");jdbc.update("UPDATE customers SET status='INACTIVE' WHERE id=?",c);service.process(event());assertEquals(0,jobs());}
    @Test void staleOrUnrelatedStockAndNewWaitlistsDoNotQueue(){
        waiter(merchant,"WAITING","OPT_IN");
        when(catalog.notificationStock(eq(merchant),eq(variant),anyString())).thenReturn(stock(false));service.process(event());assertEquals(0,jobs());
        when(catalog.notificationStock(eq(merchant),eq(variant),anyString())).thenReturn(Optional.empty());service.process(event());assertEquals(0,jobs());
        when(catalog.notificationStock(eq(merchant),eq(variant),anyString())).thenReturn(stock(true));
        jdbc.update("UPDATE waitlist_requests SET created_at=now()+interval '1 minute' WHERE merchant_id=?",merchant);service.process(event());assertEquals(0,jobs());
    }
    @Test void stopAfterQueueIsRecheckedAndCannotBeReversedByResult(){
        UUID customer=waiter(merchant,"WAITING","OPT_IN");service.process(event());var request=authorization();
        consent.changeForMerchant(merchant,customer,ConsentStatus.OPT_OUT,"CUSTOMER_MESSAGE");
        assertFalse(service.authorize(request).getEligible());
        assertEquals("SKIPPED",jdbc.queryForObject("SELECT status FROM notification_jobs WHERE merchant_id=?",String.class,merchant));
        assertEquals("OPTED_OUT",jdbc.queryForObject("SELECT status FROM waitlist_requests WHERE merchant_id=?",String.class,merchant));
    }
    @Test void stockChangeBeforeSendSkipsThenLaterRestockIsDistinct(){
        waiter(merchant,"WAITING","OPT_IN");service.process(event());var first=authorization();
        when(catalog.notificationStock(eq(merchant),eq(variant),anyString())).thenReturn(stock(false));
        assertEquals("stock_unavailable",service.authorize(first).getReason());
        when(catalog.notificationStock(eq(merchant),eq(variant),anyString())).thenReturn(stock(true));service.process(event());assertEquals(2,jobs());
    }
    @Test void authorizationAndOutcomeAreDurableTenantBoundAndIdempotent(){
        waiter(merchant,"WAITING","OPT_IN");service.process(event());var request=authorization();
        assertFalse(service.authorize(request.toBuilder().setMerchantId(other.toString()).build()).getEligible());
        assertTrue(service.authorize(request).getEligible());
        var result=CompleteNotificationRequest.newBuilder().setMerchantId(merchant.toString()).setNotificationId(request.getNotificationId()).setStatus("ACCEPTED").setProviderMessageId("synthetic.provider").build();
        service.complete(result);service.complete(result);
        assertEquals("NOTIFIED",jdbc.queryForObject("SELECT status FROM waitlist_requests WHERE merchant_id=?",String.class,merchant));
        assertNotNull(jdbc.queryForObject("SELECT notified_at FROM waitlist_requests WHERE merchant_id=?",java.sql.Timestamp.class,merchant));
        service.process(event());assertEquals(1,jobs());assertFalse(service.authorize(request).getEligible());
    }
    @Test void catalogFailureRollsBackInboxAndJobsThenRecovers(){
        waiter(merchant,"WAITING","OPT_IN");var e=event();
        when(catalog.notificationStock(eq(merchant),eq(variant),anyString())).thenThrow(new IllegalStateException());assertThrows(IllegalStateException.class,()->service.process(e));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM availability_events WHERE id=?",Integer.class,UUID.fromString(e.eventId())));
        when(catalog.notificationStock(eq(merchant),eq(variant),anyString())).thenReturn(stock(true));service.process(e);assertEquals(1,jobs());
    }
    @Test void eventValidationRejectsUnsupportedVersionBeforePersistence(){var e=event();assertThrows(IllegalArgumentException.class,()->service.process(new StockAvailable(e.eventId(),e.eventType(),2,e.occurredAt(),e.merchantId(),e.productId(),e.variantId(),e.sku(),0,5,e.correlationId())));assertEquals(0,jobs());}
    @Test void requiredQuantitiesCannotBeOmitted(){
        var payload=json.writeValueAsString(event()).replace("\"previousQuantity\":0,", "");
        var parsed=json.readValue(payload,StockAvailable.class);
        assertThrows(IllegalArgumentException.class,parsed::validate);
    }
}
