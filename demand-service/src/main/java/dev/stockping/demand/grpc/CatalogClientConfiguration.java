package dev.stockping.demand.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class CatalogClientConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel catalogChannel(@Value("${catalog.grpc-host}") String host,
            @Value("${catalog.grpc-port}") int port, @Value("${catalog.grpc-tls:false}") boolean tls) {
        if (host.isBlank() || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid Catalog gRPC address");
        var builder = ManagedChannelBuilder.forAddress(host, port).maxInboundMessageSize(256 * 1024);
        if (tls) builder.useTransportSecurity(); else builder.usePlaintext();
        return builder.build();
    }
}
