package dev.stockping.catalog.repository;
import dev.stockping.catalog.domain.Inventory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
public interface InventoryRepository extends JpaRepository<Inventory, UUID>, JpaSpecificationExecutor<Inventory> {
    java.util.Optional<Inventory> findByVariantIdAndMerchantId(UUID variantId, UUID merchantId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select i from Inventory i where i.variantId = :variantId and i.merchantId = :merchantId")
    java.util.Optional<Inventory> findForUpdate(UUID variantId, UUID merchantId);
}
