package dev.stockping.catalog.repository;
import dev.stockping.catalog.domain.Product;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {
    java.util.Optional<Product> findByIdAndMerchantId(UUID id, UUID merchantId);
}
