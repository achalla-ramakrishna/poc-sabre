package com.rewardsiq.publish;

import com.rewardsiq.audit.AuditService;
import com.rewardsiq.common.config.RewardsIqProperties;
import com.rewardsiq.common.config.RewardsIqProperties.PublishMode;
import com.rewardsiq.pricing.RulesChangedEvent;
import com.rewardsiq.rule.RedemptionRule;
import com.rewardsiq.rule.RuleRepository;
import com.rewardsiq.rule.RuleStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains the outbox (UC-06). For each PENDING row whose time has come it calls the configured
 * {@link RulePublisher}, then records the outcome. Safe to run on several instances: the outbox
 * row's @Version makes a second worker's update fail instead of double-processing it, and DPE
 * de-duplicates by idempotency key anyway.
 */
@Component
public class PublisherWorker {

    private static final Logger log = LoggerFactory.getLogger(PublisherWorker.class);

    private final PublicationRepository publications;
    private final RuleRepository rules;
    private final Map<PublishMode, RulePublisher> publishers;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final RewardsIqProperties props;
    private final Clock clock;

    public PublisherWorker(PublicationRepository publications, RuleRepository rules, List<RulePublisher> publishers,
                           AuditService audit, ApplicationEventPublisher events, TransactionTemplate tx,
                           RewardsIqProperties props, Clock clock) {
        this.publications = publications;
        this.rules = rules;
        this.publishers = publishers.stream().collect(Collectors.toMap(RulePublisher::mode, Function.identity()));
        this.audit = audit;
        this.events = events;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${rewards.publish.poll-interval}")
    public void drain() {
        List<RulePublication> due = publications.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAt(
                PublicationStatus.PENDING, clock.instant(), PageRequest.of(0, props.publish().batchSize()));
        for (RulePublication pub : due) {
            try {
                process(pub);
            } catch (RuntimeException ex) {
                // One bad row must not stop the others; it stays PENDING and is retried next poll.
                log.error("Unexpected error publishing {}", pub.getIdempotencyKey(), ex);
            }
        }
    }

    void process(RulePublication pub) {
        RedemptionRule rule = rules.findByAirlineAndRuleIdAndVersion(pub.getAirline(), pub.getRuleId(), pub.getVersion())
                .orElseThrow(() -> new IllegalStateException("Outbox row without rule: " + pub.getIdempotencyKey()));
        RulePublisher publisher = publishers.get(props.publish().mode());
        long start = System.nanoTime();
        PublishOutcome outcome = pub.getOperation() == Operation.UPSERT
                ? publisher.upsert(rule, pub.getIdempotencyKey())
                : publisher.retire(rule, pub.getIdempotencyKey());
        long millis = (System.nanoTime() - start) / 1_000_000;
        Instant now = clock.instant();

        // Java 17: pattern matching for instanceof (switch type patterns need Java 21).
        if (outcome instanceof PublishOutcome.Acked acked) {
            tx.executeWithoutResult(status -> complete(pub, rule, acked.reference(), now));
            events.publishEvent(new RulesChangedEvent(rule.getAirline(), rule.getRuleId()));
            log.info("Rule {} v{} {} acknowledged by {} (ref {}, attempt {}, {} ms)", rule.getRuleId(),
                    rule.getVersion(), pub.getOperation(), publisher.mode(), acked.reference(),
                    pub.getAttempts(), millis);
        } else if (outcome instanceof PublishOutcome.Rejected rejected) {
            pub.markFailed("Rejected: " + rejected.reason(), now);
            publications.save(pub);
            audit.ruleAction(rule, "PUBLISH_FAILED", "system", rule.getStatus(), Map.of("reason", rejected.reason()));
            log.warn("DPE rejected rule {} v{}: {}", rule.getRuleId(), rule.getVersion(), rejected.reason());
        } else if (outcome instanceof PublishOutcome.Retryable retryable) {
            if (pub.getAttempts() + 1 >= props.publish().maxAttempts()) {
                pub.markFailed("Gave up after " + (pub.getAttempts() + 1) + " attempts: " + retryable.reason(), now);
                publications.save(pub);
                audit.ruleAction(rule, "PUBLISH_FAILED", "system", rule.getStatus(),
                        Map.of("reason", retryable.reason()));
                // ERROR: this needs a person (alert NFR-O3 keys off this message).
                log.error("Publishing rule {} v{} FAILED after {} attempts: {}", rule.getRuleId(),
                        rule.getVersion(), pub.getAttempts(), retryable.reason());
            } else {
                Instant next = now.plus(Backoff.delay(props.publish().baseBackoff(), pub.getAttempts()));
                pub.scheduleRetry(retryable.reason(), next);
                publications.save(pub);
                log.warn("Publishing rule {} v{} will retry at {} (attempt {}): {}", rule.getRuleId(),
                        rule.getVersion(), next, pub.getAttempts(), retryable.reason());
            }
        }
    }

    /** Outbox ACKED + rule state change in one transaction. */
    private void complete(RulePublication pub, RedemptionRule rule, String reference, Instant now) {
        pub.markAcked(reference, now);
        publications.save(pub);
        RuleStatus before = rule.getStatus();
        if (pub.getOperation() == Operation.UPSERT) {
            rules.findByAirlineAndRuleIdOrderByVersionDesc(rule.getAirline(), rule.getRuleId()).stream()
                    .filter(old -> old.getStatus() == RuleStatus.PUBLISHED && old.getVersion() != rule.getVersion())
                    .forEach(old -> {
                        old.markSuperseded();
                        rules.save(old);
                    });
            rule.markPublished(now);
            rules.save(rule);
            audit.ruleAction(rule, "PUBLISHED", "system", before, Map.of("dpeReference", reference));
        } else {
            rule.markRetired();
            rules.save(rule);
            audit.ruleAction(rule, "RETIRED", "system", before, Map.of("dpeReference", reference));
        }
    }
}
