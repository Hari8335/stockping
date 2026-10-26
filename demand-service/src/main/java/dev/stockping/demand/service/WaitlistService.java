package dev.stockping.demand.service;

import dev.stockping.demand.domain.*;
import dev.stockping.demand.dto.DemandDtos.*;
import dev.stockping.demand.exception.DemandException;
import dev.stockping.demand.grpc.CatalogClient;
import dev.stockping.demand.repository.*;
import dev.stockping.security.Identity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@Transactional(readOnly = true)
public class WaitlistService {
    private final CustomerService customers;
    private final CustomerRepository customerRepository;
    private final ConsentService consent;
    private final CatalogClient catalog;
    private final WaitlistRepository waitlists;
    private final DemandAuditRepository audits;
    public WaitlistService(CustomerService customers, CustomerRepository customerRepository, ConsentService consent,
            CatalogClient catalog, WaitlistRepository waitlists, DemandAuditRepository audits) {
        this.customers = customers; this.customerRepository = customerRepository; this.consent = consent;
        this.catalog = catalog; this.waitlists = waitlists; this.audits = audits;
    }

    @Transactional(timeout = 20)
    public WaitlistView create(@NotNull @Valid CreateWaitlistRequest request) {
        Identity identity = Identity.current();
        Customer customer = customers.findOrCreateLocked(identity.merchantId(), request.customerPhone(), request.displayName(), request.language());
        consent.ensureEligible(customer, request.consent());
        catalog.verifyUnavailable(identity, request.variantId());
        if (waitlists.existsByMerchantIdAndCustomerIdAndVariantIdAndStatusIn(identity.merchantId(), customer.getId(), request.variantId(), WaitlistStatus.ACTIVE))
            throw new DemandException(409, "DUPLICATE_ACTIVE_WAITLIST", "Customer is already waiting for this variant");
        var waitlist = waitlists.saveAndFlush(new WaitlistRequest(identity.merchantId(), customer.getId(), request.variantId()));
        audits.save(new DemandAudit(identity.merchantId(), "WAITLIST_CREATED", waitlist.getId(), Map.of("variantId", request.variantId().toString())));
        return view(waitlist, customer);
    }

    public WaitlistView detail(UUID id) {
        UUID merchant = Identity.current().merchantId();
        var waitlist = waitlists.findByIdAndMerchantId(id, merchant).orElseThrow(DemandException::missing);
        return view(waitlist, customerRepository.findByIdAndMerchantId(waitlist.getCustomerId(), merchant).orElseThrow(DemandException::missing));
    }

    public PageView<WaitlistView> list(WaitlistStatus status, UUID variant, UUID customer,
            Instant from, Instant to, int page, int size) {
        if (page < 0 || page > 1000000 || size < 1 || size > 100 || (from != null && to != null && from.isAfter(to)))
            throw new IllegalArgumentException("Invalid range or pagination");
        UUID merchant = Identity.current().merchantId();
        Specification<WaitlistRequest> spec = (root, query, builder) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(builder.equal(root.get("merchantId"), merchant));
            if (status != null) predicates.add(builder.equal(root.get("status"), status));
            if (variant != null) predicates.add(builder.equal(root.get("variantId"), variant));
            if (customer != null) predicates.add(builder.equal(root.get("customerId"), customer));
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), to));
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var result = waitlists.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        var items = result.getContent().stream().map(w -> view(w,
                customerRepository.findByIdAndMerchantId(w.getCustomerId(), merchant).orElseThrow(DemandException::missing))).toList();
        return new PageView<>(items, result.getTotalElements(), page, size);
    }

    public DemandSummary summary() {
        var variants = waitlists.counts(Identity.current().merchantId(), WaitlistStatus.ACTIVE).stream()
                .map(c -> new VariantDemand(c.getVariantId(), c.getRequestCount(), c.getWaitingCount(), c.getActiveCount())).toList();
        return new DemandSummary(variants.stream().mapToLong(VariantDemand::activeCount).sum(),
                variants.stream().mapToLong(VariantDemand::waitingCount).sum(), variants);
    }

    private WaitlistView view(WaitlistRequest waitlist, Customer customer) {
        var status = consent.effective(waitlist.getMerchantId(), customer.getId()).orElse(null);
        return new WaitlistView(waitlist.getId(), customer.getId(), customer.getWhatsappPhone(), customer.getDisplayName(),
                waitlist.getVariantId(), waitlist.getStatus(), waitlist.getCreatedAt(), waitlist.getUpdatedAt(), waitlist.getNotifiedAt(),
                status, status == ConsentStatus.OPT_IN && customer.getStatus() == AccountStatus.ACTIVE && waitlist.getStatus().isActive());
    }
}
