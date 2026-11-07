package dev.stockping.demand.grpc;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "demand.grpc-enabled", havingValue = "true")
public class DemandGrpcServer implements SmartLifecycle {
    private final DemandGrpcService service;
    private final int port;
    private final DemandAuthenticationInterceptor authentication;
    private volatile Server server;
    public DemandGrpcServer(DemandGrpcService service, DemandAuthenticationInterceptor authentication, @Value("${demand.grpc-port}") int port, @Value("${demand.service-token:}") String token) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid gRPC port");
        if (token.length() < 32) throw new IllegalArgumentException("Demand service token must contain at least 32 characters");
        this.service = service; this.port = port; this.authentication = authentication;
    }
    @Override public void start() {
        try {
            server = NettyServerBuilder.forPort(port).maxInboundMessageSize(64 * 1024).addService(io.grpc.ServerInterceptors.intercept(service, authentication)).build().start();
        } catch (IOException e) { throw new IllegalStateException("Cannot start Demand gRPC server",e); }
    }
    @Override public void stop() {
        if (server == null) return;
        server.shutdown();
        try { if (!server.awaitTermination(5, TimeUnit.SECONDS)) server.shutdownNow(); }
        catch (InterruptedException e) { server.shutdownNow(); Thread.currentThread().interrupt(); }
    }
    @Override public boolean isRunning() { return server != null && !server.isShutdown(); }
    public int getPort() { return server == null ? port : server.getPort(); }
}
