package dev.stockping.catalog.grpc;

import dev.stockping.contracts.catalog.v1.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogServiceAuthenticationTest {
    static final String TOKEN="catalog-reader-service-token-32-characters";Server server;ManagedChannel channel;UUID observed;
    @BeforeEach void setup()throws Exception{var service=new CatalogServiceGrpc.CatalogServiceImplBase(){@Override public void getInventory(GetInventoryRequest r,StreamObserver<GetInventoryResponse> o){observed=CatalogAuthenticationInterceptor.IDENTITY.get().merchantId();o.onNext(GetInventoryResponse.getDefaultInstance());o.onCompleted();}};server=NettyServerBuilder.forPort(0).addService(ServerInterceptors.intercept(service,new CatalogAuthenticationInterceptor(mock(JwtDecoder.class),TOKEN))).build().start();channel=ManagedChannelBuilder.forAddress("localhost",server.getPort()).usePlaintext().build();}
    @AfterEach void close()throws Exception{channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);}
    void call(String token,String merchant){Metadata h=new Metadata();h.put(Metadata.Key.of("x-service-token",Metadata.ASCII_STRING_MARSHALLER),token);if(merchant!=null)h.put(Metadata.Key.of("x-merchant-id",Metadata.ASCII_STRING_MARSHALLER),merchant);CatalogServiceGrpc.newBlockingStub(channel).withDeadlineAfter(2,TimeUnit.SECONDS).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(h)).getInventory(GetInventoryRequest.getDefaultInstance());}
    @Test void authenticatedServiceSuppliesTrustedMerchant(){UUID id=UUID.randomUUID();call(TOKEN,id.toString());assertEquals(id,observed);}
    @Test void wrongCredentialRejected(){assertEquals(Status.Code.UNAUTHENTICATED,assertThrows(StatusRuntimeException.class,()->call("wrong",UUID.randomUUID().toString())).getStatus().getCode());assertNull(observed);}
    @Test void invalidMerchantRejected(){assertEquals(Status.Code.UNAUTHENTICATED,assertThrows(StatusRuntimeException.class,()->call(TOKEN,"1-1-1-1-1")).getStatus().getCode());assertNull(observed);}
    @Test void missingMerchantRejected(){assertEquals(Status.Code.UNAUTHENTICATED,assertThrows(StatusRuntimeException.class,()->call(TOKEN,null)).getStatus().getCode());assertNull(observed);}
}
