package dev.stockping.demand;

import dev.stockping.contracts.demand.v1.*;
import dev.stockping.demand.domain.*;
import dev.stockping.demand.grpc.DemandGrpcServer;
import dev.stockping.demand.repository.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"demand.grpc-enabled=true","demand.grpc-port=0","demand.service-token=test-service-token-more-than-32-characters"})
class CustomerMessageIntegrationTest extends PostgresTest {
    @Autowired DemandGrpcServer server;
    @Autowired MerchantRepository merchants;
    @Autowired CustomerRepository customers;
    @Autowired ConsentRecordRepository consents;
    @Autowired WaitlistRepository waitlists;
    @Autowired JdbcTemplate jdbc;
    UUID merchant,other; ManagedChannel channel; DemandServiceGrpc.DemandServiceBlockingStub client;
    @BeforeEach void setup(){
        merchant=UUID.randomUUID();other=UUID.randomUUID();merchants.saveAndFlush(new Merchant(merchant,"Message shop","UTC"));merchants.saveAndFlush(new Merchant(other,"Other shop","UTC"));
        channel=ManagedChannelBuilder.forAddress("localhost",server.getPort()).usePlaintext().build();Metadata headers=new Metadata();headers.put(Metadata.Key.of("x-service-token",Metadata.ASCII_STRING_MARSHALLER),"test-service-token-more-than-32-characters");client=DemandServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }
    @AfterEach void close()throws Exception{channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);}
    ProcessCustomerMessageResponse send(String text){return client.withDeadlineAfter(3,TimeUnit.SECONDS).processCustomerMessage(ProcessCustomerMessageRequest.newBuilder().setMerchantId(merchant.toString()).setCustomerPhone("+94770000000").setProviderMessageId("wamid."+UUID.randomUUID()).setMessageText(text).setReceivedAt(Instant.now().toString()).setCorrelationId(UUID.randomUUID().toString()).build());}
    @Test void helpDoesNotCreateCustomer(){assertEquals("HELP",send("HELP").getPurpose());assertTrue(customers.findByMerchantIdAndWhatsappPhone(merchant,"+94770000000").isEmpty());}
    @Test void stopWithoutCustomerDoesNotCreateState(){assertEquals("OPT_OUT",send("STOP").getPurpose());assertTrue(customers.findByMerchantIdAndWhatsappPhone(merchant,"+94770000000").isEmpty());}
    @Test void stopUsesExistingConsentTransactionAndPreservesOtherTenant(){
        UUID a=seed(merchant), b=seed(other);
        assertEquals("OPT_OUT",send("STOP NOTIFICATIONS").getPurpose());
        assertEquals(ConsentStatus.OPT_OUT,consents.findFirstByMerchantIdAndCustomerIdOrderBySequenceDesc(merchant,a).orElseThrow().getStatus());
        assertEquals("CUSTOMER_MESSAGE",consents.findFirstByMerchantIdAndCustomerIdOrderBySequenceDesc(merchant,a).orElseThrow().getSource());
        assertEquals(ConsentStatus.OPT_IN,consents.findFirstByMerchantIdAndCustomerIdOrderBySequenceDesc(other,b).orElseThrow().getStatus());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM waitlist_requests WHERE merchant_id=? AND status='WAITING'",Integer.class,merchant));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM waitlist_requests WHERE merchant_id=? AND status='WAITING'",Integer.class,other));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM demand_audit WHERE merchant_id=? AND event_type='OPT_OUT'",Integer.class,merchant));
    }
    UUID seed(UUID tenant){UUID customer=UUID.randomUUID();Instant now=Instant.now();jdbc.update("INSERT INTO customers(id,merchant_id,whatsapp_phone,display_name,status,created_at,updated_at) VALUES(?,?,'+94770000000','Test','ACTIVE',?,?)",customer,tenant,java.sql.Timestamp.from(now),java.sql.Timestamp.from(now));consents.saveAndFlush(new ConsentRecord(tenant,customer,ConsentStatus.OPT_IN));waitlists.saveAndFlush(new WaitlistRequest(tenant,customer,UUID.randomUUID()));return customer;}
}
