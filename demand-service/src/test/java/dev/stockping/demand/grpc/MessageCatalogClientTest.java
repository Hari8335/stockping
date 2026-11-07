package dev.stockping.demand.grpc;

import dev.stockping.contracts.catalog.v1.*;
import dev.stockping.demand.exception.DemandException;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class MessageCatalogClientTest {
    Server server;ManagedChannel channel;MessageCatalogClient client;
    final UUID merchant=UUID.randomUUID(), variant=UUID.randomUUID();final String correlation=UUID.randomUUID().toString();
    String mode="available";boolean authenticated, propagated, bounded;
    @BeforeEach void setup() throws Exception {
        ServerInterceptor interceptor=new ServerInterceptor(){@Override public<Q,S>ServerCall.Listener<Q>interceptCall(ServerCall<Q,S> call,Metadata headers,ServerCallHandler<Q,S> next){
            authenticated="test-catalog-service-credential-32-characters".equals(headers.get(Metadata.Key.of("x-service-token",Metadata.ASCII_STRING_MARSHALLER))) && merchant.toString().equals(headers.get(Metadata.Key.of("x-merchant-id",Metadata.ASCII_STRING_MARSHALLER)));
            propagated=correlation.equals(headers.get(Metadata.Key.of("x-request-id",Metadata.ASCII_STRING_MARSHALLER)));bounded=Context.current().getDeadline()!=null;return next.startCall(call,headers);
        }};
        var service=new CatalogServiceGrpc.CatalogServiceImplBase(){
            @Override public void resolveProductVariant(ResolveProductVariantRequest r,StreamObserver<ResolveProductVariantResponse> o){
                assertEquals("Exact-SKU",r.getSku());assertEquals(merchant.toString(),r.getMerchantId());
                if(mode.equals("unavailable")){o.onError(Status.UNAVAILABLE.asRuntimeException());return;}
                if(mode.equals("timeout")){return;}
                var response=ResolveProductVariantResponse.newBuilder().setStatus(mode.equals("missing")?ResolutionStatus.NOT_FOUND:ResolutionStatus.MATCHED);
                if(!mode.equals("missing"))response.setTotalMatches(1).addCandidates(Variant.newBuilder().setVariantId(variant.toString()).setSku("Exact-SKU").setProductName("Phone"));
                o.onNext(response.build());o.onCompleted();
            }
            @Override public void getInventory(GetInventoryRequest r,StreamObserver<GetInventoryResponse> o){
                int quantity=mode.equals("empty")?0:5;o.onNext(GetInventoryResponse.newBuilder().setVariantId(variant.toString()).setQuantity(quantity).setAvailabilityStatus(mode.equals("corrupt")?AvailabilityStatus.UNAVAILABLE:quantity==0?AvailabilityStatus.UNAVAILABLE:AvailabilityStatus.AVAILABLE).build());o.onCompleted();
            }
        };
        server=NettyServerBuilder.forPort(0).addService(ServerInterceptors.intercept(service,interceptor)).build().start();channel=ManagedChannelBuilder.forAddress("localhost",server.getPort()).usePlaintext().build();client=new MessageCatalogClient(channel,"test-catalog-service-credential-32-characters",2000);
    }
    @AfterEach void close() throws Exception{channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);}
    @Test void exactAvailableWithTrustedHeadersAndDeadline(){assertTrue(client.lookup(merchant,"Exact-SKU",correlation).orElseThrow().available());assertTrue(authenticated);assertTrue(propagated);assertTrue(bounded);}
    @Test void exactUnavailable(){mode="empty";assertFalse(client.lookup(merchant,"Exact-SKU",correlation).orElseThrow().available());}
    @Test void missing(){mode="missing";assertTrue(client.lookup(merchant,"Exact-SKU",correlation).isEmpty());}
    @Test void corruptStock(){mode="corrupt";assertThrows(DemandException.class,()->client.lookup(merchant,"Exact-SKU",correlation));}
    @Test void unavailable(){mode="unavailable";assertThrows(DemandException.class,()->client.lookup(merchant,"Exact-SKU",correlation));}
    @Test void deadline(){mode="timeout";client=new MessageCatalogClient(channel,"test-catalog-service-credential-32-characters",100);long start=System.nanoTime();assertThrows(DemandException.class,()->client.lookup(merchant,"Exact-SKU",correlation));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1500);}
}
