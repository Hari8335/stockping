package dev.stockping.demand.repository;

import dev.stockping.demand.domain.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface WaitlistRepository extends JpaRepository<WaitlistRequest, UUID>, JpaSpecificationExecutor<WaitlistRequest> {
    Optional<WaitlistRequest> findByIdAndMerchantId(UUID id, UUID merchant);
    boolean existsByMerchantIdAndCustomerIdAndVariantIdAndStatusIn(UUID merchant, UUID customer, UUID variant, Collection<WaitlistStatus> states);
    List<WaitlistRequest> findByMerchantIdAndCustomerIdAndStatusIn(UUID merchant, UUID customer, Collection<WaitlistStatus> states);

    interface VariantCounts {
        UUID getVariantId();
        long getRequestCount();
        long getWaitingCount();
        long getActiveCount();
    }

    @Query("""
            select w.variantId as variantId, count(w) as requestCount,
                sum(case when w.status = dev.stockping.demand.domain.WaitlistStatus.WAITING then 1 else 0 end) as waitingCount,
                sum(case when w.status in :active then 1 else 0 end) as activeCount
            from WaitlistRequest w where w.merchantId = :merchant
            group by w.variantId order by w.variantId
            """)
    List<VariantCounts> counts(UUID merchant, Collection<WaitlistStatus> active);
}
