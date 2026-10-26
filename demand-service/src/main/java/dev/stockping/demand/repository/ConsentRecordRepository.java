package dev.stockping.demand.repository;
import dev.stockping.demand.domain.ConsentRecord;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {
    Optional<ConsentRecord> findFirstByMerchantIdAndCustomerIdOrderBySequenceDesc(UUID merchant, UUID customer);
    List<ConsentRecord> findByMerchantIdAndCustomerIdOrderBySequenceAsc(UUID merchant, UUID customer);
}
