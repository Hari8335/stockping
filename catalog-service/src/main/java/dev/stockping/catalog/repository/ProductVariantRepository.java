package dev.stockping.catalog.repository;
import dev.stockping.catalog.domain.ProductVariant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID>, JpaSpecificationExecutor<ProductVariant> {
    java.util.Optional<ProductVariant> findByIdAndMerchantId(UUID id, UUID merchantId);
    java.util.List<ProductVariant> findByProductIdAndMerchantIdOrderById(UUID productId, UUID merchantId);
    boolean existsByMerchantIdAndSkuAndIdNot(UUID merchantId, String sku, UUID id);
}
