package dev.stockping.catalog.grpc;

import dev.stockping.catalog.service.CatalogService;
import dev.stockping.contracts.catalog.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogGrpcServiceTest {
    @Test void unexpectedFailuresMapToSanitizedInternalStatus() {
        var service=mock(CatalogService.class); UUID merchant=UUID.randomUUID(), variant=UUID.randomUUID();
        when(service.getInventory(variant)).thenThrow(new IllegalStateException("private database connection details"));
        var handler=new CatalogGrpcService(service,()->merchant);
        handler.getInventory(GetInventoryRequest.newBuilder().setMerchantId(merchant.toString()).setVariantId(variant.toString()).build(),
                new StreamObserver<GetInventoryResponse>() {
                    @Override public void onNext(GetInventoryResponse value) { fail("Unexpected success"); }
                    @Override public void onCompleted() { fail("Unexpected completion"); }
                    @Override public void onError(Throwable error) {
                        assertEquals(Status.Code.INTERNAL,Status.fromThrowable(error).getCode());
                        assertEquals("An unexpected error occurred",Status.fromThrowable(error).getDescription());
                    }
                });
    }
}
