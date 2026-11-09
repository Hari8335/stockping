package dev.stockping.demand;

import dev.stockping.contracts.catalog.v1.ResolutionStatus;
import dev.stockping.demand.ai.*;
import dev.stockping.demand.domain.*;
import dev.stockping.demand.exception.DemandException;
import dev.stockping.demand.grpc.MessageCatalogClient;
import dev.stockping.demand.messaging.CustomerMessageService;
import dev.stockping.demand.repository.*;
import dev.stockping.demand.service.ConsentService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerMessageServiceTest {
    final UUID merchant = UUID.randomUUID();
    MerchantRepository merchants;
    CustomerRepository customers;
    ConsentService consent;
    MessageCatalogClient catalog;
    WaitlistRepository waitlists;
    DemandAuditRepository audits;
    CustomerMessageService service;

    @BeforeEach
    void setup() {
        merchants = mock(MerchantRepository.class);
        customers = mock(CustomerRepository.class);
        consent = mock(ConsentService.class);
        catalog = mock(MessageCatalogClient.class);
        waitlists = mock(WaitlistRepository.class);
        audits = mock(DemandAuditRepository.class);
        service = new CustomerMessageService(merchants, customers, consent, catalog);
        when(merchants.findById(merchant)).thenReturn(Optional.of(new Merchant(merchant, "Shop", "UTC")));
    }

    CustomerMessageService.Message message(String text) {
        return new CustomerMessageService.Message(merchant, "+94770000000", "Test", "wamid.test", text, Instant.now(), UUID.randomUUID().toString());
    }

    @Test
    void help() {
        var reply = service.process(message(" help "));
        assertEquals("HELP", reply.purpose());
        assertTrue(reply.text().contains("STOCK <SKU>"));
        verifyNoInteractions(catalog, consent);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hello", "Do you have Samsung?", "STOCK", "STOCK two words"})
    void fallback(String input) {
        assertEquals("FALLBACK", service.process(message(input)).purpose());
        verifyNoInteractions(catalog, consent);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STOP", "stop notifications"})
    void unknownCustomerOptOutCreatesNothing(String input) {
        var reply = service.process(message(input));
        assertEquals("OPT_OUT", reply.purpose());
        verifyNoInteractions(consent, catalog);
        verify(customers).findByMerchantIdAndWhatsappPhone(merchant, "+94770000000");
        verifyNoMoreInteractions(customers);
    }

    @Test
    void existingCustomerUsesConsentService() {
        Customer customer = mock(Customer.class);
        UUID id = UUID.randomUUID();
        when(customer.getId()).thenReturn(id);
        when(customers.findByMerchantIdAndWhatsappPhone(merchant, "+94770000000")).thenReturn(Optional.of(customer));
        service.process(message("STOP"));
        verify(consent).changeForMerchant(merchant, id, ConsentStatus.OPT_OUT, "CUSTOMER_MESSAGE");
    }

    @Test
    void exactStockAvailableAndUnavailable() {
        var msg = message("STOCK Exact-SKU");
        when(catalog.lookup(merchant, "Exact-SKU", msg.correlationId())).thenReturn(Optional.of(new MessageCatalogClient.Stock("Phone", true)));
        assertTrue(service.process(msg).text().contains("currently available"));
        when(catalog.lookup(merchant, "Exact-SKU", msg.correlationId())).thenReturn(Optional.of(new MessageCatalogClient.Stock("Phone", false)));
        assertTrue(service.process(msg).text().contains("currently out of stock"));
    }

    @Test
    void stockNotFound() {
        when(catalog.lookup(any(), anyString(), anyString())).thenReturn(Optional.empty());
        assertTrue(service.process(message("STOCK X")).text().contains("not found"));
    }

    @Test
    void catalogOutageNeverGuessesStock() {
        when(catalog.lookup(any(), anyString(), anyString())).thenThrow(new DemandException(503, "CATALOG_UNAVAILABLE", "unavailable"));
        assertEquals("STOCK_UNAVAILABLE", service.process(message("STOCK X")).purpose());
    }

    @Test
    void unknownMerchantRejected() {
        when(merchants.findById(merchant)).thenReturn(Optional.empty());
        assertThrows(DemandException.class, () -> service.process(message("HELP")));
        verifyNoInteractions(customers, catalog, consent);
    }

    // ==========================================
    // Stage 5 AI & Waitlist Workflow Tests
    // ==========================================

    private CustomerMessageService createAiEnabledService() {
        AiProperties props = new AiProperties();
        props.setEnabled(true);
        props.setProvider("mock");
        RuleBasedAiExtractor extractor = new RuleBasedAiExtractor();
        return new CustomerMessageService(merchants, customers, consent, catalog, waitlists, audits, extractor, props);
    }

    @Test
    void naturalLanguageStockQueryAvailable() {
        CustomerMessageService aiService = createAiEnabledService();
        var msg = message("machan s25 ultra black 512 thiyenawada?");
        UUID variantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        var candidate = new MessageCatalogClient.SearchCandidate(variantId, productId, "S25U-512-BLK", "Samsung Galaxy S25 Ultra", "Samsung", Map.of("storage", "512GB", "color", "Black"));
        when(catalog.search(eq(merchant), anyString(), anyString(), isNull(), anyMap(), eq(msg.correlationId())))
                .thenReturn(new MessageCatalogClient.SearchOutcome(ResolutionStatus.MATCHED, List.of(candidate), 1));
        when(catalog.getInventory(merchant, variantId, msg.correlationId()))
                .thenReturn(new MessageCatalogClient.VariantInventory(variantId, 5, true));

        var reply = aiService.process(msg);
        assertEquals("STOCK_AVAILABLE", reply.purpose());
        assertTrue(reply.text().contains("Samsung Galaxy S25 Ultra (S25U-512-BLK) is currently available."));
    }

    @Test
    void naturalLanguageStockQueryOutOfStockOffersWaitlist() {
        CustomerMessageService aiService = createAiEnabledService();
        var msg = message("s25u blk 512 thiyenawada");
        UUID variantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        var candidate = new MessageCatalogClient.SearchCandidate(variantId, productId, "S25U-512-BLK", "Samsung Galaxy S25 Ultra", "Samsung", Map.of("storage", "512GB", "color", "Black"));
        when(catalog.search(eq(merchant), anyString(), anyString(), isNull(), anyMap(), eq(msg.correlationId())))
                .thenReturn(new MessageCatalogClient.SearchOutcome(ResolutionStatus.MATCHED, List.of(candidate), 1));
        when(catalog.getInventory(merchant, variantId, msg.correlationId()))
                .thenReturn(new MessageCatalogClient.VariantInventory(variantId, 0, false));

        var reply = aiService.process(msg);
        assertEquals("STOCK_WAITLIST_OFFER", reply.purpose());
        assertTrue(reply.text().contains("currently out of stock"));
        assertTrue(reply.text().contains("Reply YES or send WAITLIST S25U-512-BLK"));
    }

    @Test
    void scenarioA_replyYesAfterOutOfStockJoinsWaitlist() {
        CustomerMessageService aiService = createAiEnabledService();
        var queryMsg = message("Do you have S25 Ultra 512 black?");
        UUID variantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        var candidate = new MessageCatalogClient.SearchCandidate(variantId, productId, "S25U-512-BLK", "Samsung Galaxy S25 Ultra", "Samsung", Map.of("storage", "512GB", "color", "Black"));
        when(catalog.search(eq(merchant), any(), any(), any(), anyMap(), eq(queryMsg.correlationId())))
                .thenReturn(new MessageCatalogClient.SearchOutcome(ResolutionStatus.MATCHED, List.of(candidate), 1));
        when(catalog.getInventory(merchant, variantId, queryMsg.correlationId()))
                .thenReturn(new MessageCatalogClient.VariantInventory(variantId, 0, false));

        // Step 1: Customer asks, receives out-of-stock offer
        var reply1 = aiService.process(queryMsg);
        assertEquals("STOCK_WAITLIST_OFFER", reply1.purpose());

        // Step 2: Customer replies "YES"
        var yesMsg = message("YES");
        when(catalog.search(eq(merchant), isNull(), isNull(), eq("S25U-512-BLK"), anyMap(), eq(yesMsg.correlationId())))
                .thenReturn(new MessageCatalogClient.SearchOutcome(ResolutionStatus.MATCHED, List.of(candidate), 1));
        when(catalog.getInventory(merchant, variantId, yesMsg.correlationId()))
                .thenReturn(new MessageCatalogClient.VariantInventory(variantId, 0, false));

        Customer customer = new Customer(merchant, yesMsg.phone(), yesMsg.displayName(), "en");
        UUID customerId = customer.getId();
        when(customers.findByMerchantIdAndWhatsappPhone(merchant, yesMsg.phone())).thenReturn(Optional.of(customer));
        when(waitlists.existsByMerchantIdAndCustomerIdAndVariantIdAndStatusIn(eq(merchant), eq(customerId), eq(variantId), anySet()))
                .thenReturn(false);
        when(waitlists.saveAndFlush(any(WaitlistRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var reply2 = aiService.process(yesMsg);
        assertEquals("WAITLIST_CONFIRMED", reply2.purpose());
        assertTrue(reply2.text().contains("added to the waitlist"));
        verify(consent).changeForMerchant(merchant, customerId, ConsentStatus.OPT_IN, "CUSTOMER_MESSAGE");
        verify(waitlists).saveAndFlush(any(WaitlistRequest.class));
    }

    @Test
    void explicitWaitlistCommandAddsToWaitlist() {
        CustomerMessageService aiService = createAiEnabledService();
        var msg = message("WAITLIST S25U-512-BLK");
        UUID variantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        var candidate = new MessageCatalogClient.SearchCandidate(variantId, productId, "S25U-512-BLK", "Samsung Galaxy S25 Ultra", "Samsung", Map.of("storage", "512GB", "color", "Black"));
        when(catalog.search(eq(merchant), isNull(), isNull(), eq("S25U-512-BLK"), anyMap(), eq(msg.correlationId())))
                .thenReturn(new MessageCatalogClient.SearchOutcome(ResolutionStatus.MATCHED, List.of(candidate), 1));
        when(catalog.getInventory(merchant, variantId, msg.correlationId()))
                .thenReturn(new MessageCatalogClient.VariantInventory(variantId, 0, false));

        Customer customer = new Customer(merchant, msg.phone(), msg.displayName(), "en");
        UUID customerId = customer.getId();
        when(customers.findByMerchantIdAndWhatsappPhone(merchant, msg.phone())).thenReturn(Optional.of(customer));
        when(waitlists.existsByMerchantIdAndCustomerIdAndVariantIdAndStatusIn(eq(merchant), eq(customerId), eq(variantId), anySet()))
                .thenReturn(false);
        when(waitlists.saveAndFlush(any(WaitlistRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var reply = aiService.process(msg);
        assertEquals("WAITLIST_CONFIRMED", reply.purpose());
        assertTrue(reply.text().contains("Samsung Galaxy S25 Ultra (S25U-512-BLK)"));
        verify(consent).changeForMerchant(merchant, customerId, ConsentStatus.OPT_IN, "CUSTOMER_MESSAGE");
    }

    @Test
    void ambiguousQueryReturnsClarificationPrompt() {
        CustomerMessageService aiService = createAiEnabledService();
        var msg = message("Do you have the silver 512 one?");
        var reply = aiService.process(msg);
        assertEquals("CLARIFICATION", reply.purpose());
        assertEquals("Which phone model are you looking for?", reply.text());
        verifyNoInteractions(catalog);
    }

    @Test
    void aiOptOutIntentRecordsOptOut() {
        CustomerMessageService aiService = createAiEnabledService();
        var msg = message("stop sending alerts");
        Customer customer = mock(Customer.class);
        UUID customerId = UUID.randomUUID();
        when(customer.getId()).thenReturn(customerId);
        when(customers.findByMerchantIdAndWhatsappPhone(merchant, msg.phone())).thenReturn(Optional.of(customer));

        var reply = aiService.process(msg);
        assertEquals("OPT_OUT", reply.purpose());
        verify(consent).changeForMerchant(merchant, customerId, ConsentStatus.OPT_OUT, "CUSTOMER_MESSAGE");
    }
}
