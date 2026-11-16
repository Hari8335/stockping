package dev.stockping.catalog.service;

import dev.stockping.catalog.domain.*;
import dev.stockping.catalog.dto.CatalogDtos.*;
import dev.stockping.catalog.repository.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CatalogServiceTest {
    UUID merchant=UUID.randomUUID(), variantId=UUID.randomUUID();
    ProductRepository products=mock(ProductRepository.class);
    ProductVariantRepository variants=mock(ProductVariantRepository.class);
    InventoryRepository inventories=mock(InventoryRepository.class);
    StockEventRepository events=mock(StockEventRepository.class);
    CatalogService service=new CatalogService(products,variants,inventories,events,()->merchant,mock(dev.stockping.catalog.messaging.StockOutbox.class));
    @Test void productsAreOrderedNewestFirstWithDeterministicTieBreaker() {
        when(products.findAll(org.mockito.ArgumentMatchers.<Specification<Product>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        service.listProducts(null, null, null, 0, 20);
        var ordering = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(products).findAll(org.mockito.ArgumentMatchers.<Specification<Product>>any(), ordering.capture());
        assertEquals(Sort.Direction.DESC, ordering.getValue().getSort().getOrderFor("createdAt").getDirection());
        assertEquals(Sort.Direction.DESC, ordering.getValue().getSort().getOrderFor("id").getDirection());
    }
    @ParameterizedTest @CsvSource({"0,NOT_FOUND", "1,MATCHED", "2,AMBIGUOUS"})
    void resolutionUsesTotalMatchesRatherThanChoosingFirstCandidate(int count, Resolution expected) {
        var product=new Product(merchant,"Phone","Brand","Category",CatalogStatus.ACTIVE);
        var content=new ArrayList<ProductVariant>();
        for(int i=0;i<count;i++) content.add(new ProductVariant(product,"SKU"+i,Map.of(),null,CatalogStatus.ACTIVE));
        when(variants.findAll(org.mockito.ArgumentMatchers.<Specification<ProductVariant>>any(),any(Pageable.class)))
                .thenReturn(new PageImpl<>(content));
        var result=service.search(new VariantCriteria("Phone",null,null,CatalogStatus.ACTIVE,Map.of()),0,20);
        assertEquals(expected,result.resolutionStatus()); assertEquals(count,result.totalMatches());
    }
    @Test void unchangedInventoryNeverSavesAnEvent() {
        when(inventories.findForUpdate(variantId,merchant)).thenReturn(Optional.of(new Inventory(merchant,variantId)));
        var result=service.updateInventory(variantId,new UpdateInventory(0));
        assertFalse(result.changed()); assertFalse(result.restockTransition()); assertNull(result.stockEventId());
        verifyNoInteractions(events);
    }
    @Test void batchLookupQueriesOnlyTheCurrentMerchantAndOmitsMissingVariants() {
        var other = UUID.randomUUID();
        var product = new Product(merchant,"Phone","Brand","Category",CatalogStatus.ACTIVE);
        var variant = mock(ProductVariant.class);
        when(variant.getId()).thenReturn(variantId);
        when(variant.getProduct()).thenReturn(product);
        when(variant.getSku()).thenReturn("SKU-1");
        when(variant.getAttributes()).thenReturn(Map.of("color","Black"));
        when(variants.findByMerchantIdAndIdIn(eq(merchant), anyCollection())).thenReturn(List.of(variant));
        var inventory = new Inventory(merchant,variantId);
        when(inventories.findByMerchantIdAndVariantIdIn(eq(merchant), anyCollection())).thenReturn(List.of(inventory));
        var result = service.variantSummaries(List.of(variantId,other));
        assertEquals(1,result.size());
        assertEquals("Phone",result.getFirst().productName());
        assertEquals(0,result.getFirst().quantity());
        verify(variants).findByMerchantIdAndIdIn(eq(merchant), argThat(ids -> ids.containsAll(List.of(variantId,other))));
        verify(inventories).findByMerchantIdAndVariantIdIn(eq(merchant), anyCollection());
        assertThrows(IllegalArgumentException.class, () -> service.variantSummaries(List.of()));
    }
    @Test void actualChangeSavesEventAndReportsRestock() {
        when(inventories.findForUpdate(variantId,merchant)).thenReturn(Optional.of(new Inventory(merchant,variantId)));
        when(variants.findByIdAndMerchantId(variantId,merchant)).thenReturn(Optional.of(mock(ProductVariant.class)));
        var result=service.updateInventory(variantId,new UpdateInventory(5));
        assertTrue(result.changed()); assertTrue(result.restockTransition()); assertNotNull(result.stockEventId());
        verify(events).saveAndFlush(argThat(e->e.getPreviousQuantity()==0 && e.getNewQuantity()==5 && e.isRestockTransition()));
    }
    @Test void invalidAttributeKeysValuesAndAliasesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->CatalogService.normalizeAttributes(Map.of("color"," ")));
        assertThrows(IllegalArgumentException.class,()->CatalogService.normalizeAttributes(Map.of("bad.key","Black")));
        assertThrows(IllegalArgumentException.class,()->CatalogService.normalizeAttributes(Map.of("color","Black","Colour","Black")));
        assertEquals(Map.of("color","Black"),CatalogService.normalizeAttributes(Map.of("Colour"," Black ")));
        var tooMany=new HashMap<String,String>(); for(int i=0;i<21;i++) tooMany.put("key"+i,"value");
        assertThrows(IllegalArgumentException.class,()->CatalogService.normalizeAttributes(tooMany));
    }
}
