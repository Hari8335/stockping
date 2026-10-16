package dev.stockping.contracts;

import dev.stockping.contracts.catalog.v1.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogContractTest {
    @Test void catalogContractHasVersionedUnaryMethodsAndRoundTripsStructuredAttributes() throws Exception {
        var request=ResolveProductVariantRequest.newBuilder().setMerchantId("merchant").setSku("SKU")
                .putAttributes("storage","512GB").putAttributes("color","Black").build();
        assertEquals(request,ResolveProductVariantRequest.parseFrom(request.toByteArray()));
        assertEquals("stockping.catalog.v1.CatalogService/ResolveProductVariant",CatalogServiceGrpc.getResolveProductVariantMethod().getFullMethodName());
        assertEquals("stockping.catalog.v1.CatalogService/GetInventory",CatalogServiceGrpc.getGetInventoryMethod().getFullMethodName());
        assertEquals(io.grpc.MethodDescriptor.MethodType.UNARY,CatalogServiceGrpc.getGetInventoryMethod().getType());
    }
}
