package dev.stockping.demand.grpc;

import dev.stockping.contracts.demand.v1.*;
import dev.stockping.demand.messaging.CustomerMessageService;
import dev.stockping.demand.service.PhoneNormalizer;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.MetadataUtils;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DemandGrpcTest {
    static final String TOKEN = "test-messaging-service-credential-32-characters";
    CustomerMessageService application; Server server; ManagedChannel channel;
    @BeforeEach void start() throws Exception {
        application = mock(CustomerMessageService.class);
        when(application.process(any())).thenReturn(new CustomerMessageService.Reply(true,"Help text","HELP"));
        server = NettyServerBuilder.forPort(0).addService(ServerInterceptors.intercept(
                new DemandGrpcService(application,new PhoneNormalizer()),new DemandAuthenticationInterceptor(TOKEN))).build().start();
        channel = ManagedChannelBuilder.forAddress("localhost",server.getPort()).usePlaintext().build();
    }
    @AfterEach void close() throws Exception { channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS); }
    DemandServiceGrpc.DemandServiceBlockingStub client(String token) {
        Metadata headers = new Metadata(); if (token != null) headers.put(Metadata.Key.of("x-service-token",Metadata.ASCII_STRING_MARSHALLER),token);
        return DemandServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers)).withDeadlineAfter(2,TimeUnit.SECONDS);
    }
    ProcessCustomerMessageRequest.Builder request() {return ProcessCustomerMessageRequest.newBuilder().setMerchantId(UUID.randomUUID().toString())
            .setCustomerPhone("+94770000000").setProviderMessageId("wamid.test").setMessageText("HELP")
            .setReceivedAt(Instant.now().toString()).setCorrelationId(UUID.randomUUID().toString());}
    @Test void validAuthenticationSerializationAndCorrelation() {
        var request=request().build();var reply=client(TOKEN).processCustomerMessage(request);
        assertEquals(ReplyAction.SEND_TEXT,reply.getAction());assertEquals("HELP",reply.getPurpose());assertEquals("Help text",reply.getReplyText());
        assertEquals(request.getCorrelationId(),reply.getCorrelationId());
        verify(application).process(argThat(m -> m.merchant().toString().equals(request.getMerchantId()) && m.correlationId().equals(request.getCorrelationId()) && m.phone().equals(request.getCustomerPhone())));
    }
    @Test void missingCredential() { assertCode(Status.Code.UNAUTHENTICATED, () -> client(null).processCustomerMessage(request().build()));verifyNoInteractions(application); }
    @Test void invalidCredential() { assertCode(Status.Code.UNAUTHENTICATED, () -> client("invalid").processCustomerMessage(request().build()));verifyNoInteractions(application); }
    @ParameterizedTest @ValueSource(strings={"uuid","phone","text","time","correlation","provider"})
    void invalidRequest(String field) {
        var r=request();switch(field){case "uuid"->r.setMerchantId("1-1-1-1-1");case "phone"->r.setCustomerPhone("0770000000");case "text"->r.setMessageText(" ");case "time"->r.setReceivedAt("wrong");case "correlation"->r.setCorrelationId("wrong");case "provider"->r.setProviderMessageId("");}
        assertCode(Status.Code.INVALID_ARGUMENT, () -> client(TOKEN).processCustomerMessage(r.build()));verifyNoInteractions(application);
    }
    @Test void noReplyMapping() {when(application.process(any())).thenReturn(new CustomerMessageService.Reply(false,"","IGNORED"));assertEquals(ReplyAction.NO_REPLY,client(TOKEN).processCustomerMessage(request().build()).getAction());}
    @Test void unexpectedErrorsAreSanitized() {when(application.process(any())).thenThrow(new IllegalStateException("private database details"));var error=assertThrows(StatusRuntimeException.class,()->client(TOKEN).processCustomerMessage(request().build()));assertEquals(Status.Code.INTERNAL,error.getStatus().getCode());assertFalse(error.getMessage().contains("private"));}
    private static void assertCode(Status.Code code, org.junit.jupiter.api.function.Executable action) {assertEquals(code,assertThrows(StatusRuntimeException.class,action).getStatus().getCode());}
}
