package com.rewardsiq.grpc;

import com.rewardsiq.common.config.RewardsIqProperties;
import com.rewardsiq.pricing.grpc.RedemptionPricingGrpcService;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Netty gRPC server for DPE (port rewards.grpc.port), started and stopped with Spring. */
@Component
@ConditionalOnProperty(prefix = "rewards.grpc", name = "enabled", havingValue = "true")
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final RedemptionPricingGrpcService pricing;
    private final ServiceAuthInterceptor auth;
    private final int port;
    private Server server;

    public GrpcServerLifecycle(RedemptionPricingGrpcService pricing, ServiceAuthInterceptor auth,
                               RewardsIqProperties props) {
        this.pricing = pricing;
        this.auth = auth;
        this.port = props.grpc().port();
    }

    @Override
    public void start() {
        try {
            server = NettyServerBuilder.forPort(port)
                    .addService(ServerInterceptors.intercept(pricing, auth))
                    .maxInboundMessageSize(1024 * 1024)
                    .build()
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start gRPC server on port " + port, e);
        }
        log.info("gRPC RedemptionPricing listening on port {}", port);
    }

    @Override
    public void stop() {
        if (server == null) {
            return;
        }
        server.shutdown();
        try {
            if (!server.awaitTermination(10, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }
}
