package com.rewardsiq.publish;

import com.rewardsiq.common.config.RewardsIqProperties.PublishMode;
import com.rewardsiq.rule.RedemptionRule;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Transactional outbox row: written in the same transaction as the rule's status change, then
 * delivered to DPE by {@link PublisherWorker}. At-least-once delivery + idempotency key on DPE's
 * side = each rule version applied exactly once.
 */
@Document("rulePublications")
@CompoundIndex(name = "worker_queue", def = "{'status': 1, 'nextAttemptAt': 1}")
@CompoundIndex(name = "by_rule", def = "{'airline': 1, 'ruleId': 1, 'version': -1}")
public class RulePublication {

    @Id
    private String id;
    private String airline;
    private String ruleId;
    private int version;
    @Indexed(unique = true)
    private String idempotencyKey;
    private Operation operation;
    private PublishMode mode;
    private PublicationStatus status;
    private int attempts;
    private Instant nextAttemptAt;
    private String lastError;
    private String dpeReference;
    /** TTL: publication history is kept 2 years (expireAfter set on the index). */
    @Indexed(name = "ttl_createdAt", expireAfter = "730d")
    private Instant createdAt;
    private Instant completedAt;
    @Version
    private Long revision;

    protected RulePublication() {
    }

    public RulePublication(RedemptionRule rule, Operation operation, PublishMode mode, Instant now) {
        this.airline = rule.getAirline();
        this.ruleId = rule.getRuleId();
        this.version = rule.getVersion();
        this.idempotencyKey = rule.idempotencyKey() + (operation == Operation.RETIRE ? ":retire" : "");
        this.operation = operation;
        this.mode = mode;
        this.status = PublicationStatus.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    void markAcked(String reference, Instant now) {
        this.attempts++;
        this.status = PublicationStatus.ACKED;
        this.dpeReference = reference;
        this.lastError = null;
        this.completedAt = now;
    }

    void scheduleRetry(String error, Instant next) {
        this.attempts++;
        this.lastError = error;
        this.nextAttemptAt = next;
    }

    void markFailed(String error, Instant now) {
        this.attempts++;
        this.status = PublicationStatus.FAILED;
        this.lastError = error;
        this.completedAt = now;
    }

    public String getId() { return id; }
    public String getAirline() { return airline; }
    public String getRuleId() { return ruleId; }
    public int getVersion() { return version; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Operation getOperation() { return operation; }
    public PublishMode getMode() { return mode; }
    public PublicationStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public String getDpeReference() { return dpeReference; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
