package dev.stockping.demand.ai;

import dev.stockping.contracts.catalog.v1.ResolutionStatus;
import dev.stockping.demand.controller.AiController;
import dev.stockping.demand.dto.AiDtos.AiQueryRequest;
import dev.stockping.demand.dto.AiDtos.AiQueryResponse;
import dev.stockping.demand.grpc.MessageCatalogClient;
import dev.stockping.security.Identity;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiControllerTest {

    private final UUID merchantId = UUID.randomUUID();
    private AiExtractor aiExtractor;
    private MessageCatalogClient catalog;
    private AiProperties properties;
    private AiController controller;

    @BeforeEach
    void setup() {
        aiExtractor = mock(AiExtractor.class);
        catalog = mock(MessageCatalogClient.class);
        properties = new AiProperties();
        properties.setEnabled(true);
        controller = new AiController(aiExtractor, catalog, properties);
    }

    private <T> T withMerchantIdentity(java.util.function.Supplier<T> action) {
        org.springframework.security.oauth2.jwt.Jwt jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("mock-token")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .claim("merchant_id", merchantId.toString())
                .claim("role", "MERCHANT_ADMIN")
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(3600))
                .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt, java.util.List.of()));
        try {
            return action.get();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void queryPreviewMatchedAvailableProduct() {
        withMerchantIdentity(() -> {
            UUID variantId = UUID.randomUUID();
            UUID productId = UUID.randomUUID();

            when(aiExtractor.extract(eq("S25 Ultra 512 black available?"), anyString()))
                    .thenReturn(new ProductExtractionResult(
                            AiIntent.CHECK_STOCK, "S25 Ultra", "Samsung", "Galaxy S25 Ultra",
                            Map.of("storage", "512GB", "color", "Black"), 0.94, false, "", "mock", 12
                    ));

            var candidate = new MessageCatalogClient.SearchCandidate(
                    variantId, productId, "S25U-512-BLK", "Samsung Galaxy S25 Ultra", "Samsung",
                    Map.of("storage", "512GB", "color", "Black")
            );
            when(catalog.search(eq(merchantId), eq("Galaxy S25 Ultra"), eq("Samsung"), isNull(), anyMap(), anyString()))
                    .thenReturn(new MessageCatalogClient.SearchOutcome(ResolutionStatus.MATCHED, List.of(candidate), 1));
            when(catalog.getInventory(eq(merchantId), eq(variantId), anyString()))
                    .thenReturn(new MessageCatalogClient.VariantInventory(variantId, 10, true));

            AiQueryResponse response = controller.queryPreview(new AiQueryRequest("S25 Ultra 512 black available?"));

            assertNotNull(response);
            assertEquals(AiIntent.CHECK_STOCK, response.intent());
            assertEquals("MATCHED", response.catalogStatus());
            assertNotNull(response.matchedVariant());
            assertEquals("S25U-512-BLK", response.matchedVariant().sku());
            assertTrue(response.matchedVariant().available());
            assertEquals(10, response.matchedVariant().quantity());
            assertEquals("STOCK_AVAILABLE", response.purpose());
            assertTrue(response.suggestedReply().contains("currently available"));
            assertNotNull(response.disclaimer());
            return null;
        });
    }

    @Test
    void queryPreviewOutOfStockGeneratesWaitlistOffer() {
        withMerchantIdentity(() -> {
            UUID variantId = UUID.randomUUID();
            UUID productId = UUID.randomUUID();

            when(aiExtractor.extract(eq("machan s25 ultra black 512 thiyenawada?"), anyString()))
                    .thenReturn(new ProductExtractionResult(
                            AiIntent.CHECK_STOCK, "S25 Ultra", "Samsung", "Galaxy S25 Ultra",
                            Map.of("storage", "512GB", "color", "Black"), 0.94, false, "", "mock", 15
                    ));

            var candidate = new MessageCatalogClient.SearchCandidate(
                    variantId, productId, "S25U-512-BLK", "Samsung Galaxy S25 Ultra", "Samsung",
                    Map.of("storage", "512GB", "color", "Black")
            );
            when(catalog.search(eq(merchantId), eq("Galaxy S25 Ultra"), eq("Samsung"), isNull(), anyMap(), anyString()))
                    .thenReturn(new MessageCatalogClient.SearchOutcome(ResolutionStatus.MATCHED, List.of(candidate), 1));
            when(catalog.getInventory(eq(merchantId), eq(variantId), anyString()))
                    .thenReturn(new MessageCatalogClient.VariantInventory(variantId, 0, false));

            AiQueryResponse response = controller.queryPreview(new AiQueryRequest("machan s25 ultra black 512 thiyenawada?"));

            assertNotNull(response);
            assertEquals("STOCK_WAITLIST_OFFER", response.purpose());
            assertFalse(response.matchedVariant().available());
            assertTrue(response.suggestedReply().contains("currently out of stock"));
            assertTrue(response.suggestedReply().contains("WAITLIST S25U-512-BLK"));
            return null;
        });
    }

    @Test
    void queryPreviewAmbiguousReturnsCandidateList() {
        withMerchantIdentity(() -> {
            UUID v1 = UUID.randomUUID();
            UUID v2 = UUID.randomUUID();
            UUID prod = UUID.randomUUID();

            when(aiExtractor.extract(eq("Do you have S25 Ultra?"), anyString()))
                    .thenReturn(new ProductExtractionResult(
                            AiIntent.CHECK_STOCK, "S25 Ultra", "Samsung", "Galaxy S25 Ultra",
                            Map.of(), 0.85, false, "", "mock", 8
                    ));

            var c1 = new MessageCatalogClient.SearchCandidate(v1, prod, "S25U-256", "Samsung Galaxy S25 Ultra 256GB", "Samsung", Map.of("storage", "256GB"));
            var c2 = new MessageCatalogClient.SearchCandidate(v2, prod, "S25U-512", "Samsung Galaxy S25 Ultra 512GB", "Samsung", Map.of("storage", "512GB"));

            when(catalog.search(eq(merchantId), eq("Galaxy S25 Ultra"), eq("Samsung"), isNull(), anyMap(), anyString()))
                    .thenReturn(new MessageCatalogClient.SearchOutcome(ResolutionStatus.AMBIGUOUS, List.of(c1, c2), 2));

            AiQueryResponse response = controller.queryPreview(new AiQueryRequest("Do you have S25 Ultra?"));

            assertNotNull(response);
            assertEquals("AMBIGUOUS", response.catalogStatus());
            assertTrue(response.needsClarification());
            assertEquals("STOCK_AMBIGUOUS", response.purpose());
            assertTrue(response.suggestedReply().contains("multiple options"));
            return null;
        });
    }

    @Test
    void queryPreviewOptOutIntent() {
        withMerchantIdentity(() -> {
            when(aiExtractor.extract(eq("stop sending alerts"), anyString()))
                    .thenReturn(new ProductExtractionResult(
                            AiIntent.OPT_OUT, "", "", "", Map.of(), 0.99, false, "", "mock", 5
                    ));

            AiQueryResponse response = controller.queryPreview(new AiQueryRequest("stop sending alerts"));

            assertNotNull(response);
            assertEquals(AiIntent.OPT_OUT, response.intent());
            assertEquals("OPT_OUT", response.purpose());
            assertTrue(response.suggestedReply().contains("will not receive restock notifications"));
            verifyNoInteractions(catalog);
            return null;
        });
    }
}
