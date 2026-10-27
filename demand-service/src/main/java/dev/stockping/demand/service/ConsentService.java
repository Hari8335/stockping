package dev.stockping.demand.service;

import dev.stockping.demand.domain.*;
import dev.stockping.demand.dto.DemandDtos.ConsentView;
import dev.stockping.demand.exception.DemandException;
import dev.stockping.demand.repository.*;
import dev.stockping.security.Identity;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true)
public class ConsentService {
    private final CustomerService customers;
    private final ConsentRecordRepository records;
    private final WaitlistRepository waitlists;
    private final DemandAuditRepository audits;
    public ConsentService(CustomerService customers, ConsentRecordRepository records, WaitlistRepository waitlists, DemandAuditRepository audits) {
        this.customers = customers; this.records = records; this.waitlists = waitlists; this.audits = audits;
    }

    public Optional<ConsentStatus> effective(UUID merchant, UUID customer) {
        return records.findFirstByMerchantIdAndCustomerIdOrderBySequenceDesc(merchant, customer).map(ConsentRecord::getStatus);
    }

    /** Caller holds the customer lock; a historical opt-out requires the explicit re-consent endpoint. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ensureEligible(Customer customer, boolean initialOptIn) {
        if (customer.getStatus() != AccountStatus.ACTIVE)
            throw new DemandException(409, "CUSTOMER_INACTIVE", "Customer is inactive");
        var status = effective(customer.getMerchantId(), customer.getId());
        if (status.orElse(null) == ConsentStatus.OPT_OUT)
            throw new DemandException(409, "CUSTOMER_OPTED_OUT", "Customer opted out; capture explicit renewed consent first");
        if (status.isEmpty()) {
            if (!initialOptIn) throw new DemandException(409, "CONSENT_REQUIRED", "Customer consent is required");
            capture(customer, ConsentStatus.OPT_IN);
        }
    }

    @Transactional
    public ConsentView change(UUID customerId, ConsentStatus status) {
        return changeForMerchant(Identity.current().merchantId(), customerId, status, "MANUAL_API");
    }

    /** Internal application entry point; caller authenticates and establishes merchant scope. */
    @Transactional
    public ConsentView changeForMerchant(UUID merchant, UUID customerId, ConsentStatus status, String source) {
        Customer customer = customers.lockOwned(merchant, customerId);
        if (status == ConsentStatus.OPT_IN && customer.getStatus() != AccountStatus.ACTIVE)
            throw new DemandException(409, "CUSTOMER_INACTIVE", "Customer is inactive");
        var record = capture(customer, status, source);
        int changed = 0;
        if (status == ConsentStatus.OPT_OUT) {
            var active = waitlists.findByMerchantIdAndCustomerIdAndStatusIn(merchant, customerId, WaitlistStatus.ACTIVE);
            active.forEach(WaitlistRequest::optOut);
            changed = active.size();
        }
        return new ConsentView(customerId, status, record.getCapturedAt(), changed);
    }

    private ConsentRecord capture(Customer customer, ConsentStatus status) {
        return capture(customer, status, "MANUAL_API");
    }

    private ConsentRecord capture(Customer customer, ConsentStatus status, String source) {
        var record = records.saveAndFlush(new ConsentRecord(customer.getMerchantId(), customer.getId(), status, source));
        audits.save(new DemandAudit(customer.getMerchantId(), status.name(), customer.getId(), Map.of("source", source)));
        return record;
    }
}
