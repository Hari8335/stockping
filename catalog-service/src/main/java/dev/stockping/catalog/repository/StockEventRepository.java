package dev.stockping.catalog.repository;
import dev.stockping.catalog.domain.StockEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
public interface StockEventRepository extends JpaRepository<StockEvent, UUID>, JpaSpecificationExecutor<StockEvent> {
    java.util.List<StockEvent> findByVariantIdAndMerchantIdOrderByCreatedAtAsc(UUID variantId, UUID merchantId);
}
