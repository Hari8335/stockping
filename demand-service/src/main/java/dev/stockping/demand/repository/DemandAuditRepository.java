package dev.stockping.demand.repository;
import dev.stockping.demand.domain.DemandAudit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DemandAuditRepository extends JpaRepository<DemandAudit, UUID> {}
