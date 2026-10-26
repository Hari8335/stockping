package dev.stockping.catalog.grpc;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class CatalogGrpcServer implements SmartLifecycle {
    private final CatalogGrpcService service;
    private final int port;
    private final CatalogAuthenticationInterceptor authentication;
    private volatile Server server;
    public CatalogGrpcServer(CatalogGrpcService service, CatalogAuthenticationInterceptor authentication, @Value("${catalog.grpc-port}") int port) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid gRPC port");
        this.service = service; this.port = port; this.authentication = authentication;
    }
    @Override public void start() {
        try {
            server = NettyServerBuilder.forPort(port).maxInboundMessageSize(64 * 1024).addService(io.grpc.ServerInterceptors.intercept(service, authentication)).build().start();
        } catch (IOException e) { throw new IllegalStateException("Cannot start Catalog gRPC server",e); }
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
