package dev.stockping.catalog;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import dev.stockping.security.TokenConfiguration;
import java.time.Instant;
import java.util.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

final class TestTokens {
    static final String SECRET = "test-only-signing-secret-at-least-32-bytes-long";
    static String token(UUID merchant) { return token(merchant, "MERCHANT_ADMIN", 1800); }
    static String token(UUID merchant, String role, long expiry) {
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new TokenConfiguration().signingKey(SECRET)));
        var claims = JwtClaimsSet.builder().issuer(TokenConfiguration.ISSUER).audience(List.of(TokenConfiguration.AUDIENCE))
                .subject(UUID.randomUUID().toString()).claim("merchant_id", merchant.toString()).claim("role", role)
                .issuedAt(Instant.now().minusSeconds(120)).expiresAt(Instant.now().plusSeconds(expiry)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
