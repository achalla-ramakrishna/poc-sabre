package com.rewardsiq.publish;

import static org.assertj.core.api.Assertions.assertThat;

import com.rewardsiq.TestRules;
import com.rewardsiq.common.config.RewardsIqProperties;
import com.rewardsiq.grpc.publish.v1.RedemptionRuleSinkGrpc;
import com.rewardsiq.grpc.publish.v1.RetireRuleRequest;
import com.rewardsiq.grpc.publish.v1.RuleAck;
import com.rewardsiq.grpc.publish.v1.UpsertRuleRequest;
import com.rewardsiq.publish.dpe.DpeGrpcPublisher;
import com.rewardsiq.rule.RedemptionRule;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Real gRPC client against an in-process fake DPE: status-code mapping, headers, payload. */
class DpeGrpcPublisherTest {

    private final AtomicReference<UpsertRuleRequest> received = new AtomicReference<>();
    private final AtomicReference<Metadata> headers = new AtomicReference<>();
    private Server server;
    private ManagedChannel channel;
    private final RedemptionRule rule = approved();

    private static RedemptionRule approved() {
        RedemptionRule r = TestRules.draft("rul_A", TestRules.sydPerEconomyChoice());
        r.submit("a");
        r.approve("b", TestRules.NOW);
        return r;
    }

    private DpeGrpcPublisher publisherAnswering(Function<UpsertRuleRequest, Object> behaviour) throws Exception {
        String name = InProcessServerBuilder.generateName();
        var sink = new RedemptionRuleSinkGrpc.RedemptionRuleSinkImplBase() {
            @Override
            public void upsertRule(UpsertRuleRequest request, StreamObserver<RuleAck> response) {
                received.set(request);
                Object result = behaviour.apply(request);
                if (result instanceof Status status) {
                    response.onError(status.asRuntimeException());
                } else if (result instanceof RuleAck ack) {
                    response.onNext(ack);
                    response.onCompleted();
                }
                // else: never answer, to trigger the deadline
            }

            @Override
            public void retireRule(RetireRuleRequest request, StreamObserver<RuleAck> response) {
                response.onNext(RuleAck.newBuilder().setResult(RuleAck.Result.ACCEPTED).setDpeReference("ret-1").build());
                response.onCompleted();
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(sink, new io.grpc.ServerInterceptor() {
                    @Override
                    public <Q, R> io.grpc.ServerCall.Listener<Q> interceptCall(io.grpc.ServerCall<Q, R> call,
                            Metadata h, io.grpc.ServerCallHandler<Q, R> next) {
                        headers.set(h);
                        return next.startCall(call, h);
                    }
                }))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).build();
        return new DpeGrpcPublisher(channel,
                new RewardsIqProperties.Dpe("in-process", true, Duration.ofMillis(300), "svc-token", false, 1));
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void accepted_is_acked_and_payload_matches_the_contract() throws Exception {
        var publisher = publisherAnswering(r -> RuleAck.newBuilder()
                .setResult(RuleAck.Result.ACCEPTED).setDpeReference("dpe-7781").build());

        PublishOutcome outcome = publisher.upsert(rule, "VA:rul_A:1");

        assertThat(outcome).isEqualTo(new PublishOutcome.Acked("dpe-7781"));
        var proto = received.get().getRule();
        assertThat(proto.getRatesList()).singleElement()
                .satisfies(rate -> assertThat(rate.getPointsPerUnit()).isEqualTo("47.8"));
        assertThat(proto.getScope().getOriginsList()).containsExactly("SYD");
        assertThat(proto.getSpecificity()).isEqualTo(5);
        assertThat(headers.get().get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo("Bearer svc-token");
        assertThat(headers.get().get(Metadata.Key.of("idempotency-key", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo("VA:rul_A:1");
    }

    @Test
    void duplicate_counts_as_acked() throws Exception {
        var publisher = publisherAnswering(r -> RuleAck.newBuilder().setResult(RuleAck.Result.DUPLICATE).build());
        assertThat(publisher.upsert(rule, "k")).isInstanceOf(PublishOutcome.Acked.class);
    }

    @Test
    void invalid_argument_is_rejected_not_retried() throws Exception {
        var publisher = publisherAnswering(r -> Status.INVALID_ARGUMENT.withDescription("bad scope"));
        assertThat(publisher.upsert(rule, "k")).isInstanceOf(PublishOutcome.Rejected.class);
    }

    @Test
    void unavailable_and_deadline_are_retryable() throws Exception {
        var unavailable = publisherAnswering(r -> Status.UNAVAILABLE);
        assertThat(unavailable.upsert(rule, "k")).isInstanceOf(PublishOutcome.Retryable.class);
        stop();

        var silent = publisherAnswering(r -> null);
        assertThat(silent.upsert(rule, "k"))
                .isInstanceOfSatisfying(PublishOutcome.Retryable.class,
                        r -> assertThat(r.reason()).contains("DEADLINE_EXCEEDED"));
    }
}
