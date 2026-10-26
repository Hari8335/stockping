package dev.stockping.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration
public class TokenConfiguration {
    public static final String ISSUER = "stockping-demand";
    public static final String AUDIENCE = "stockping-merchant-api";

    @Bean
    public SecretKey signingKey(@Value("${auth.jwt-secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalArgumentException("AUTH_JWT_SECRET must contain at least 32 bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey key) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(ISSUER), TokenConfiguration::claims));
        return decoder;
    }

    private static OAuth2TokenValidatorResult claims(Jwt jwt) {
        try {
            canonicalUuid(jwt.getSubject());
            canonicalUuid(jwt.getClaimAsString("merchant_id"));
            if (jwt.getExpiresAt() == null || jwt.getIssuedAt() == null
                    || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    || jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(30))
                    || !jwt.getAudience().contains(AUDIENCE)
                    || jwt.getClaimAsString("role") == null) throw new IllegalArgumentException();
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException e) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid identity claims", null));
        }
    }

    private static void canonicalUuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
    }
}
