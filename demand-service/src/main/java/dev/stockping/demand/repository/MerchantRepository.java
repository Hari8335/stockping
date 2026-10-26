package dev.stockping.demand.repository;
import dev.stockping.demand.domain.Merchant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MerchantRepository extends JpaRepository<Merchant, UUID> {}
