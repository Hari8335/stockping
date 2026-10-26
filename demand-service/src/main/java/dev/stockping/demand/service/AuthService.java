package dev.stockping.demand.service;

import dev.stockping.demand.domain.AccountStatus;
import dev.stockping.demand.dto.DemandDtos.*;
import dev.stockping.demand.exception.DemandException;
import dev.stockping.demand.repository.*;
import dev.stockping.security.TokenConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final MerchantRepository merchants;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final long expirySeconds;
    private final String dummyHash;
    public AuthService(UserRepository users, MerchantRepository merchants, PasswordEncoder passwords, JwtEncoder encoder,
            @Value("${auth.jwt-expiry-seconds}") long expirySeconds) {
        if (expirySeconds < 60 || expirySeconds > 3600) throw new IllegalArgumentException("JWT expiry must be 60..3600 seconds");
        this.users = users; this.merchants = merchants; this.passwords = passwords; this.encoder = encoder;
        this.expirySeconds = expirySeconds;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        // BCrypt's input bound is bytes, not Java characters.
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) throw invalidCredentials();
        var user = users.findByEmail(request.email().trim().toLowerCase(Locale.ROOT));
        boolean matches = passwords.matches(request.password(), user.map(u -> u.getPasswordHash()).orElse(dummyHash));
        if (user.isEmpty() || !matches || user.get().getStatus() != AccountStatus.ACTIVE) throw invalidCredentials();
        var admin = user.get();
        var merchant = merchants.findById(admin.getMerchantId()).orElseThrow(AuthService::invalidCredentials);
        if (merchant.getStatus() != AccountStatus.ACTIVE) throw invalidCredentials();
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(TokenConfiguration.ISSUER).audience(List.of(TokenConfiguration.AUDIENCE))
                .subject(admin.getId().toString()).claim("merchant_id", admin.getMerchantId().toString()).claim("role", admin.getRole())
                .issuedAt(now).expiresAt(now.plusSeconds(expirySeconds)).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResponse(token, claims.getExpiresAt(), admin.getId(), admin.getMerchantId(), admin.getRole());
    }

    private static DemandException invalidCredentials() { return new DemandException(401, "INVALID_CREDENTIALS", "Email or password is incorrect"); }
}
