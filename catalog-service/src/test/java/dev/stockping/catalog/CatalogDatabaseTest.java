package dev.stockping.catalog;

import dev.stockping.catalog.domain.*;
import dev.stockping.catalog.repository.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogDatabaseTest extends PostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductRepository products;
    @Autowired ProductVariantRepository variants;
    @Autowired InventoryRepository inventories;
    @Autowired StockEventRepository events;
    UUID a=UUID.randomUUID(), b=UUID.randomUUID();
    @BeforeEach void clean() { jdbc.execute("TRUNCATE stock_events, inventory, product_variants, products"); }
    Product product(UUID merchant) { return products.saveAndFlush(new Product(merchant,"Phone","Brand","Category",CatalogStatus.ACTIVE)); }
    ProductVariant variant(Product p, String sku) { return variants.saveAndFlush(new ProductVariant(p,sku,Map.of("color","Black"),null,CatalogStatus.ACTIVE)); }
    @Test void cleanMigrationAndAllEntitiesRoundTrip() {
        assertEquals(1,jdbc.queryForObject("select count(*) from flyway_schema_history where success",Integer.class));
        Product p=product(a); ProductVariant v=variant(p,"SKU");
        assertEquals("Phone",products.findByIdAndMerchantId(p.getId(),a).orElseThrow().getName());
        assertEquals(Map.of("color","Black"),variants.findByIdAndMerchantId(v.getId(),a).orElseThrow().getAttributes());
        Inventory i=new Inventory(a,v.getId()); var event=i.setQuantity(5).orElseThrow();
        inventories.saveAndFlush(i); events.saveAndFlush(event);
        assertEquals(5,inventories.findByVariantIdAndMerchantId(v.getId(),a).orElseThrow().getQuantity());
        assertTrue(events.findByVariantIdAndMerchantIdOrderByCreatedAtAsc(v.getId(),a).getFirst().isRestockTransition());
        assertTrue(products.findByIdAndMerchantId(p.getId(),b).isEmpty());
        assertTrue(variants.findByIdAndMerchantId(v.getId(),b).isEmpty());
        assertTrue(variants.findByProductIdAndMerchantIdOrderById(p.getId(),b).isEmpty());
        assertTrue(inventories.findByVariantIdAndMerchantId(v.getId(),b).isEmpty());
        assertTrue(events.findByVariantIdAndMerchantIdOrderByCreatedAtAsc(v.getId(),b).isEmpty());
    }
    @Test void skuUniquenessIsScopedToMerchant() {
        Product pa=product(a), pb=product(b); variant(pa,"SAME"); variant(pb,"SAME");
        assertThrows(DataIntegrityViolationException.class,()->variant(pa,"SAME"));
        assertEquals(2,variants.count());
    }
    @Test void databasePreventsCrossMerchantParentLinks() {
        Product p=product(a); ProductVariant v=variant(p,"OWNED");
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("update product_variants set merchant_id=? where id=?",b,v.getId()));
        assertThrows(DataIntegrityViolationException.class,()->inventories.saveAndFlush(new Inventory(b,v.getId())));
        var foreignInventory=new Inventory(b,v.getId()); var foreignEvent=foreignInventory.setQuantity(5).orElseThrow();
        assertThrows(DataIntegrityViolationException.class,()->events.saveAndFlush(foreignEvent));
    }
    @Test void databasePreventsNegativeAndContradictoryInventoryAndDuplicateRows() {
        var v=variant(product(a),"INV"); inventories.saveAndFlush(new Inventory(a,v.getId()));
        assertThrows(DataIntegrityViolationException.class,()->inventories.saveAndFlush(new Inventory(a,v.getId())));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("update inventory set quantity=-1 where variant_id=?",v.getId()));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("update inventory set availability_status='AVAILABLE' where variant_id=?",v.getId()));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("update inventory set quantity=3 where variant_id=?",v.getId()));
        assertEquals(0,inventories.findByVariantIdAndMerchantId(v.getId(),a).orElseThrow().getQuantity());
    }
}
