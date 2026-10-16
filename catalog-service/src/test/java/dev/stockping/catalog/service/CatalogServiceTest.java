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
    ProductVariantRepository variants=mock(ProductVariantRepository.class);
    InventoryRepository inventories=mock(InventoryRepository.class);
    StockEventRepository events=mock(StockEventRepository.class);
    CatalogService service=new CatalogService(mock(ProductRepository.class),variants,inventories,events,()->merchant);
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
    @Test void actualChangeSavesEventAndReportsRestock() {
        when(inventories.findForUpdate(variantId,merchant)).thenReturn(Optional.of(new Inventory(merchant,variantId)));
        var result=service.updateInventory(variantId,new UpdateInventory(5));
        assertTrue(result.changed()); assertTrue(result.restockTransition()); assertNotNull(result.stockEventId());
        verify(events).save(argThat(e->e.getPreviousQuantity()==0 && e.getNewQuantity()==5 && e.isRestockTransition()));
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
