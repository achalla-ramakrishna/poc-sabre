package com.rewardsiq.publish.dpe;

import com.rewardsiq.common.config.RewardsIqProperties;
import com.rewardsiq.grpc.publish.v1.RedemptionRuleSinkGrpc;
import com.rewardsiq.grpc.publish.v1.RetireRuleRequest;
import com.rewardsiq.grpc.publish.v1.RuleAck;
import com.rewardsiq.grpc.publish.v1.UpsertRuleRequest;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * LOCAL ONLY (rewards.dpe.stub-enabled=true): a fake DPE RedemptionRuleSink so the full publish
 * flow works without the real DPE. It de-duplicates by idempotency key like DPE should, and
 * rejects obviously bad rules so the failure path can be tried too.
 */
@Component
@ConditionalOnProperty(prefix = "rewards.dpe", name = "stub-enabled", havingValue = "true")
public class DpeStubServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DpeStubServer.class);

    private final int port;
    private final Map<String, String> applied = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger(7000);
    private Server server;

    public DpeStubServer(RewardsIqProperties props) {
        this.port = props.dpe().stubPort();
    }

    /** Lower phase than default: start before the publisher worker's first poll. */
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 100;
    }

    @Override
    public void start() {
        try {
            server = NettyServerBuilder.forPort(port).addService(new Sink()).build().start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start DPE stub on port " + port, e);
        }
        log.warn("DPE STUB listening on port {} (local development only)", port);
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdownNow();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    private final class Sink extends RedemptionRuleSinkGrpc.RedemptionRuleSinkImplBase {

        @Override
        public void upsertRule(UpsertRuleRequest request, StreamObserver<RuleAck> response) {
            if (request.getRule().getRatesCount() == 0) {
                response.onError(Status.INVALID_ARGUMENT.withDescription("rule has no rates").asRuntimeException());
                return;
            }
            respond(request.getIdempotencyKey(), response);
        }

        @Override
        public void retireRule(RetireRuleRequest request, StreamObserver<RuleAck> response) {
            respond(request.getIdempotencyKey(), response);
        }

        private void respond(String key, StreamObserver<RuleAck> response) {
            String existing = applied.get(key);
            RuleAck ack = existing != null
                    ? RuleAck.newBuilder().setResult(RuleAck.Result.DUPLICATE).setDpeReference(existing).build()
                    : RuleAck.newBuilder().setResult(RuleAck.Result.ACCEPTED)
                    .setDpeReference(applied.computeIfAbsent(key, k -> "dpe-" + sequence.incrementAndGet())).build();
            log.info("DPE stub {} {} -> {}", key, ack.getResult(), ack.getDpeReference());
            response.onNext(ack);
            response.onCompleted();
        }
    }
}
