package dev.stockping.demand.config;

import dev.stockping.demand.domain.*;
import dev.stockping.demand.repository.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Explicit local/demo profile only. Existing users and passwords are never overwritten. */
@Component
@Profile("demo")
public class DemoBootstrap implements ApplicationRunner {
    private final MerchantRepository merchants;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final UUID merchantId;
    private final String email;
    private final String password;
    public DemoBootstrap(MerchantRepository merchants, UserRepository users, PasswordEncoder passwords,
            @Value("${demo.merchant-id}") UUID merchantId, @Value("${demo.admin-email}") String email,
            @Value("${demo.admin-password}") String password) {
        this.merchants = merchants; this.users = users; this.passwords = passwords;
        this.merchantId = merchantId; this.email = email.trim().toLowerCase(java.util.Locale.ROOT); this.password = password;
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 12 || bytes > 72 || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
            throw new IllegalArgumentException("Configure demo email and a 12..72 byte demo password");
        if (users.findByEmail(email).isPresent()) return;
        if (!merchants.existsById(merchantId)) merchants.saveAndFlush(new Merchant(merchantId, "Demo Electronics", "Asia/Colombo"));
        users.save(new User(merchantId, email, passwords.encode(password)));
    }
}
