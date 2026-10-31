package com.tejas.contentreview.contentservice.grpc;

import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Runs the gRPC server next to the HTTP server; port 0 picks a free port (used by tests). */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final ContentMetadataGrpcService service;
    private final int configuredPort;
    private final HealthStatusManager health = new HealthStatusManager();
    private Server server;

    public GrpcServerLifecycle(ContentMetadataGrpcService service, @Value("${grpc.server.port}") int configuredPort) {
        this.service = service;
        this.configuredPort = configuredPort;
    }

    @Override
    public synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = NettyServerBuilder.forPort(configuredPort)
                    .addService(service)
                    .addService(health.getHealthService())
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start gRPC server on port " + configuredPort, e);
        }
        health.setStatus("", ServingStatus.SERVING);
        log.info("gRPC server listening on port {}", server.getPort());
    }

    @Override
    public synchronized void stop() {
        if (server == null) {
            return;
        }
        health.enterTerminalState();
        server.shutdown();
        try {
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        }
        server = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    public synchronized int getPort() {
        return server == null ? -1 : server.getPort();
    }
}
