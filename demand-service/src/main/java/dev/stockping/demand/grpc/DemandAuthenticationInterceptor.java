package dev.stockping.demand.grpc;

import io.grpc.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DemandAuthenticationInterceptor implements ServerInterceptor {
    private final byte[] token;
    public DemandAuthenticationInterceptor(@Value("${demand.service-token:}") String token) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }
    @Override public <Q, S> ServerCall.Listener<Q> interceptCall(ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
        String supplied = headers.get(Metadata.Key.of("x-service-token", Metadata.ASCII_STRING_MARSHALLER));
        if (token.length < 32 || supplied == null || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
            call.close(Status.UNAUTHENTICATED.withDescription("Service authentication required"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        return next.startCall(call, headers);
    }
}
