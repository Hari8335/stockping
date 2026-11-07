package dev.stockping.catalog.grpc;

import dev.stockping.security.Identity;
import io.grpc.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

@Component
public class CatalogAuthenticationInterceptor implements ServerInterceptor {
    public static final Context.Key<Identity> IDENTITY = Context.key("stockping-identity");
    public static final Context.Key<String> REQUEST_ID = Context.key("request-id");
    private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private final JwtDecoder decoder;
    private final String serviceToken;
    public CatalogAuthenticationInterceptor(JwtDecoder decoder, @org.springframework.beans.factory.annotation.Value("${catalog.service-token:}") String serviceToken) {
        this.decoder = decoder; this.serviceToken = serviceToken;
    }

    @Override public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String bearer = headers.get(AUTHORIZATION);
        try {
            String internal = headers.get(Metadata.Key.of("x-service-token", Metadata.ASCII_STRING_MARSHALLER));
            if (internal != null) {
                if (serviceToken.length() < 32 || !java.security.MessageDigest.isEqual(serviceToken.getBytes(java.nio.charset.StandardCharsets.UTF_8), internal.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    throw new JwtException("Invalid service credential");
                String merchant = headers.get(Metadata.Key.of("x-merchant-id", Metadata.ASCII_STRING_MARSHALLER));
                if (merchant == null || !java.util.UUID.fromString(merchant).toString().equals(merchant)) throw new JwtException("Invalid merchant");
                String correlation = headers.get(Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER));
                if (correlation == null || !correlation.matches("[0-9a-f-]{36}")) correlation = java.util.UUID.randomUUID().toString();
                // This gRPC service exposes only catalogue/inventory reads. REST still requires a browser JWT.
                var identity = new Identity(null, java.util.UUID.fromString(merchant), "CATALOG_READER", null);
                return Contexts.interceptCall(Context.current().withValues(IDENTITY, identity, REQUEST_ID, correlation), call, headers, next);
            }
            if (bearer == null || !bearer.startsWith("Bearer ")) throw new JwtException("Missing bearer token");
            var identity = Identity.from(decoder.decode(bearer.substring(7)));
            if (!"MERCHANT_ADMIN".equals(identity.role())) {
                call.close(Status.PERMISSION_DENIED.withDescription("Merchant administrator role required"), new Metadata());
                return new ServerCall.Listener<>() {};
            }
            String correlation = headers.get(Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER));
            if (correlation == null || !correlation.matches("[0-9a-f-]{36}")) correlation = java.util.UUID.randomUUID().toString();
            return Contexts.interceptCall(Context.current().withValues(IDENTITY, identity, REQUEST_ID, correlation), call, headers, next);
        } catch (JwtException | IllegalArgumentException e) {
            call.close(Status.UNAUTHENTICATED.withDescription("Token is invalid or expired"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
    }
}
