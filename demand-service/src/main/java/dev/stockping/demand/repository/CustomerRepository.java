package dev.stockping.demand.repository;

import dev.stockping.demand.domain.Customer;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    Optional<Customer> findByIdAndMerchantId(UUID id, UUID merchantId);
    Optional<Customer> findByMerchantIdAndWhatsappPhone(UUID merchantId, String whatsappPhone);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id and c.merchantId = :merchant")
    Optional<Customer> lockOwned(UUID merchant, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.merchantId = :merchant and c.whatsappPhone = :phone")
    Optional<Customer> lockByPhone(UUID merchant, String phone);

    // ON CONFLICT waits for a concurrent creator before the subsequent lock/read.
    @Modifying
    @Query(value = """
            INSERT INTO customers(id, merchant_id, whatsapp_phone, display_name, language, status, created_at, updated_at)
            VALUES (:id, :merchant, :phone, :name, :language, 'ACTIVE', :now, :now)
            ON CONFLICT (merchant_id, whatsapp_phone) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(UUID id, UUID merchant, String phone, String name, String language, Instant now);
}
