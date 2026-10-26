package dev.stockping.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.util.*;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import static org.junit.jupiter.api.Assertions.*;

class TokenConfigurationTest {
    final TokenConfiguration configuration = new TokenConfiguration();
    final SecretKey key = configuration.signingKey("test-only-signing-secret-at-least-32-bytes");
    final JwtDecoder decoder = configuration.jwtDecoder(key);
    String token(String flaw) {
        var claims = JwtClaimsSet.builder().issuer(flaw.equals("issuer") ? "untrusted" : TokenConfiguration.ISSUER)
                .audience(List.of(flaw.equals("audience") ? "other-service" : TokenConfiguration.AUDIENCE))
                .subject(flaw.equals("subject") ? "bad" : UUID.randomUUID().toString());
        if (!flaw.equals("merchant")) claims.claim("merchant_id", UUID.randomUUID().toString());
        if (!flaw.equals("role")) claims.claim("role", "MERCHANT_ADMIN");
        if (!flaw.equals("issued")) claims.issuedAt(Instant.now().plusSeconds(flaw.equals("future") ? 200 : -200));
        if (!flaw.equals("missing_expiry")) claims.expiresAt(Instant.now().plusSeconds(flaw.equals("expired") ? -20 : 1800));
        var signingKey = flaw.equals("signature") ? configuration.signingKey("different-test-signing-secret-at-least-32-bytes") : key;
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(signingKey));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }
    @Test void acceptsValidIdentity() {
        var jwt = decoder.decode(token("valid"));
        assertNotNull(Identity.from(jwt).merchantId()); assertEquals("MERCHANT_ADMIN", Identity.from(jwt).role());
        assertFalse(Identity.from(jwt).toString().contains(jwt.getTokenValue()));
    }
    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "subject", "merchant", "role", "issued", "future", "missing_expiry", "expired", "signature"})
    void rejectsInvalidTokens(String flaw) { assertThrows(JwtException.class, () -> decoder.decode(token(flaw))); }
    @Test void rejectsWeakConfigurationAndMissingAuthentication() {
        assertThrows(IllegalArgumentException.class, () -> configuration.signingKey("too-short"));
        assertThrows(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class, Identity::current);
    }
}
