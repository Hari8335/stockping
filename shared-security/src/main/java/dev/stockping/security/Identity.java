package dev.stockping.security;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Authenticated claims only. Never construct merchant identity from an API payload. */
public record Identity(UUID userId, UUID merchantId, String role, String token) {
    public static Identity from(Jwt jwt) {
        return new Identity(UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("merchant_id")),
                jwt.getClaimAsString("role"), jwt.getTokenValue());
    }

    public static Identity current() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken auth
                && auth.isAuthenticated()) return from(auth.getToken());
        throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("Authentication required");
    }

    @Override public String toString() { return "Identity[authenticated]"; }
}
