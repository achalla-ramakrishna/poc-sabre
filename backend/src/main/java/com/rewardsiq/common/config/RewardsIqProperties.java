package com.rewardsiq.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Type-safe view of the rewards.* properties; invalid config fails startup. */
@Validated
@ConfigurationProperties(prefix = "rewards")
public record RewardsIqProperties(
        @NotNull Publish publish,
        @NotNull Batch batch,
        @NotNull GrpcServer grpc,
        @NotNull Dpe dpe,
        @NotNull Cache cache,
        @NotNull Security security) {

    public enum PublishMode { REALTIME, BATCH }

    /**
     * @param mode         REALTIME = gRPC to DPE; BATCH = Plan B file export (switch without a release)
     * @param pollInterval how often the worker reads PENDING outbox rows
     * @param maxAttempts  retries before a publication is marked FAILED
     * @param baseBackoff  first retry delay; doubles each attempt, with jitter
     */
    public record Publish(@NotNull PublishMode mode, @NotNull Duration pollInterval,
                          @Min(1) @Max(20) int maxAttempts, @NotNull Duration baseBackoff,
                          @Min(1) @Max(500) int batchSize) {
    }

    public record Batch(boolean enabled, @NotBlank String directory, @NotBlank String cron) {
    }

    public record GrpcServer(boolean enabled, @Min(1) int port) {
    }

    /** DPE gRPC target, e.g. "dpe.internal:443"; deadline per publish call. */
    public record Dpe(@NotBlank String target, boolean plaintext, @NotNull Duration deadline,
                      String serviceToken, boolean stubEnabled, @Min(1) int stubPort) {
    }

    public record Cache(@NotNull Duration refreshInterval) {
    }

    /**
     * @param devSecret Base64 HMAC key for locally issued tokens; leave empty in real environments
     *                  and set spring.security.oauth2.resourceserver.jwt.issuer-uri instead.
     */
    public record Security(String devSecret, @NotBlank String audience, @NotBlank String pricingScope) {
    }
}
