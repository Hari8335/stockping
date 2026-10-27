package dev.stockping.demand;

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
    final UUID merchant=UUID.randomUUID();
    MerchantRepository merchants; CustomerRepository customers; ConsentService consent; MessageCatalogClient catalog; CustomerMessageService service;
    @BeforeEach void setup(){merchants=mock(MerchantRepository.class);customers=mock(CustomerRepository.class);consent=mock(ConsentService.class);catalog=mock(MessageCatalogClient.class);service=new CustomerMessageService(merchants,customers,consent,catalog);when(merchants.findById(merchant)).thenReturn(Optional.of(new Merchant(merchant,"Shop","UTC")));}
    CustomerMessageService.Message message(String text){return new CustomerMessageService.Message(merchant,"+94770000000","Test","wamid.test",text,Instant.now(),UUID.randomUUID().toString());}
    @Test void help(){var reply=service.process(message(" help "));assertEquals("HELP",reply.purpose());assertTrue(reply.text().contains("STOCK <SKU>"));verifyNoInteractions(catalog,consent);}
    @ParameterizedTest @ValueSource(strings={"hello", "Do you have Samsung?", "STOCK", "STOCK two words"}) void fallback(String input){assertEquals("FALLBACK",service.process(message(input)).purpose());verifyNoInteractions(catalog,consent);}
    @ParameterizedTest @ValueSource(strings={"STOP","stop notifications"}) void unknownCustomerOptOutCreatesNothing(String input){var reply=service.process(message(input));assertEquals("OPT_OUT",reply.purpose());verifyNoInteractions(consent,catalog);verify(customers).findByMerchantIdAndWhatsappPhone(merchant,"+94770000000");verifyNoMoreInteractions(customers);}
    @Test void existingCustomerUsesConsentService(){Customer customer=mock(Customer.class);UUID id=UUID.randomUUID();when(customer.getId()).thenReturn(id);when(customers.findByMerchantIdAndWhatsappPhone(merchant,"+94770000000")).thenReturn(Optional.of(customer));service.process(message("STOP"));verify(consent).changeForMerchant(merchant,id,ConsentStatus.OPT_OUT,"CUSTOMER_MESSAGE");}
    @Test void exactStockAvailableAndUnavailable(){var msg=message("STOCK Exact-SKU");when(catalog.lookup(merchant,"Exact-SKU",msg.correlationId())).thenReturn(Optional.of(new MessageCatalogClient.Stock("Phone",true)));assertTrue(service.process(msg).text().endsWith("currently available."));when(catalog.lookup(merchant,"Exact-SKU",msg.correlationId())).thenReturn(Optional.of(new MessageCatalogClient.Stock("Phone",false)));assertTrue(service.process(msg).text().endsWith("currently out of stock."));}
    @Test void stockNotFound(){when(catalog.lookup(any(),anyString(),anyString())).thenReturn(Optional.empty());assertTrue(service.process(message("STOCK X")).text().contains("not found"));}
    @Test void catalogOutageNeverGuessesStock(){when(catalog.lookup(any(),anyString(),anyString())).thenThrow(new DemandException(503,"CATALOG_UNAVAILABLE","unavailable"));assertEquals("STOCK_UNAVAILABLE",service.process(message("STOCK X")).purpose());}
    @Test void unknownMerchantRejected(){when(merchants.findById(merchant)).thenReturn(Optional.empty());assertThrows(DemandException.class,()->service.process(message("HELP")));verifyNoInteractions(customers,catalog,consent);}
}
