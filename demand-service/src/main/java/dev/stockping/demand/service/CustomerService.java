package dev.stockping.demand.service;

import dev.stockping.demand.domain.Customer;
import dev.stockping.demand.exception.DemandException;
import dev.stockping.demand.repository.CustomerRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class CustomerService {
    private final CustomerRepository customers;
    private final PhoneNormalizer phones;
    public CustomerService(CustomerRepository customers, PhoneNormalizer phones) { this.customers = customers; this.phones = phones; }

    @Transactional(propagation = Propagation.MANDATORY)
    public Customer findOrCreateLocked(UUID merchant, String phone, String name, String language) {
        String normalized = phones.normalize(phone);
        customers.insertIfAbsent(UUID.randomUUID(), merchant, normalized,
                name == null || name.isBlank() ? null : name.trim(), language, Instant.now());
        return customers.lockByPhone(merchant, normalized).orElseThrow(DemandException::missing);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Customer lockOwned(UUID merchant, UUID customer) {
        return customers.lockOwned(merchant, customer).orElseThrow(DemandException::missing);
    }
}
