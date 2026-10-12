package com.rewardsiq.publish.dpe;

import com.rewardsiq.common.config.RewardsIqProperties;
import com.rewardsiq.common.config.RewardsIqProperties.PublishMode;
import com.rewardsiq.grpc.publish.v1.RedemptionRuleSinkGrpc;
import com.rewardsiq.grpc.publish.v1.RetireRuleRequest;
import com.rewardsiq.grpc.publish.v1.RuleAck;
import com.rewardsiq.grpc.publish.v1.UpsertRuleRequest;
import com.rewardsiq.publish.PublishOutcome;
import com.rewardsiq.publish.RulePublisher;
import com.rewardsiq.rule.RedemptionRule;
import io.grpc.Channel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Real-time publishing over gRPC to DPE's RedemptionRuleSink (INT-1). Maps gRPC status codes to
 * outcomes: client-side problems are Rejected (no retry), transient ones are Retryable.
 */
public class DpeGrpcPublisher implements RulePublisher {

    static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> CORRELATION = Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> IDEMPOTENCY = Metadata.Key.of("idempotency-key", Metadata.ASCII_STRING_MARSHALLER);

    private static final Set<Status.Code> RETRYABLE = EnumSet.of(Status.Code.UNAVAILABLE,
            Status.Code.DEADLINE_EXCEEDED, Status.Code.RESOURCE_EXHAUSTED, Status.Code.ABORTED,
            Status.Code.INTERNAL, Status.Code.UNKNOWN);

    private final Channel channel;
    private final RewardsIqProperties.Dpe config;

    public DpeGrpcPublisher(Channel channel, RewardsIqProperties.Dpe config) {
        this.channel = channel;
        this.config = config;
    }

    @Override
    public PublishMode mode() {
        return PublishMode.REALTIME;
    }

    @Override
    public PublishOutcome upsert(RedemptionRule rule, String idempotencyKey) {
        UpsertRuleRequest request = UpsertRuleRequest.newBuilder()
                .setIdempotencyKey(idempotencyKey)
                .setRule(DpeProtoMapper.toProto(rule))
                .build();
        return call(idempotencyKey, () -> stub(idempotencyKey).upsertRule(request));
    }

    @Override
    public PublishOutcome retire(RedemptionRule rule, String idempotencyKey) {
        RetireRuleRequest request = RetireRuleRequest.newBuilder()
                .setIdempotencyKey(idempotencyKey)
                .setAirline(rule.getAirline())
                .setRuleId(rule.getRuleId())
                .setVersion(rule.getVersion())
                .build();
        return call(idempotencyKey, () -> stub(idempotencyKey).retireRule(request));
    }

    private PublishOutcome call(String key, Supplier<RuleAck> rpc) {
        try {
            RuleAck ack = rpc.get();
            return switch (ack.getResult()) {
                case ACCEPTED, DUPLICATE -> new PublishOutcome.Acked(
                        ack.getDpeReference().isEmpty() ? ack.getResult().name() : ack.getDpeReference());
                default -> new PublishOutcome.Retryable("Unexpected ack result " + ack.getResult() + " for " + key);
            };
        } catch (StatusRuntimeException ex) {
            Status status = ex.getStatus();
            String reason = status.getCode() + (status.getDescription() == null ? "" : ": " + status.getDescription());
            return RETRYABLE.contains(status.getCode())
                    ? new PublishOutcome.Retryable(reason)
                    : new PublishOutcome.Rejected(reason);
        }
    }

    /** A stub per call: cheap, and carries this call's deadline and headers. The channel is shared. */
    private RedemptionRuleSinkGrpc.RedemptionRuleSinkBlockingStub stub(String idempotencyKey) {
        Metadata headers = new Metadata();
        if (config.serviceToken() != null && !config.serviceToken().isBlank()) {
            headers.put(AUTHORIZATION, "Bearer " + config.serviceToken());
        }
        String correlationId = MDC.get("correlationId");
        headers.put(CORRELATION, correlationId == null ? idempotencyKey : correlationId);
        headers.put(IDEMPOTENCY, idempotencyKey);
        return RedemptionRuleSinkGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(config.deadline().toMillis(), TimeUnit.MILLISECONDS);
    }
}
