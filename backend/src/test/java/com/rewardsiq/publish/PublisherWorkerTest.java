package com.rewardsiq.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rewardsiq.TestRules;
import com.rewardsiq.audit.AuditService;
import com.rewardsiq.common.config.RewardsIqProperties;
import com.rewardsiq.common.config.RewardsIqProperties.PublishMode;
import com.rewardsiq.pricing.RulesChangedEvent;
import com.rewardsiq.rule.RedemptionRule;
import com.rewardsiq.rule.RuleRepository;
import com.rewardsiq.rule.RuleStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PublisherWorkerTest {

    private final PublicationRepository publications = mock(PublicationRepository.class);
    private final RuleRepository rules = mock(RuleRepository.class);
    private final RulePublisher publisher = mock(RulePublisher.class);
    private final AuditService audit = mock(AuditService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private PublisherWorker worker;
    private RedemptionRule approved;
    private RedemptionRule previousLive;

    @BeforeEach
    void setUp() {
        when(publisher.mode()).thenReturn(PublishMode.REALTIME);
        var props = new RewardsIqProperties(
                new RewardsIqProperties.Publish(PublishMode.REALTIME, Duration.ofSeconds(5), 3, Duration.ofSeconds(30), 50),
                null, null, null, null, null);
        worker = new PublisherWorker(publications, rules, List.of(publisher), audit, events,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), props,
                Clock.fixed(TestRules.NOW, ZoneOffset.UTC));

        previousLive = TestRules.published("rul_A", TestRules.sydPerEconomyChoice());
        approved = previousLive.nextVersion("u-analyst", TestRules.NOW);
        approved.submit("u-analyst");
        approved.approve("u-approver", TestRules.NOW);
        when(rules.findByAirlineAndRuleIdAndVersion("VA", "rul_A", 2)).thenReturn(Optional.of(approved));
        when(rules.findByAirlineAndRuleIdOrderByVersionDesc("VA", "rul_A")).thenReturn(List.of(approved, previousLive));
    }

    private RulePublication pending() {
        return new RulePublication(approved, Operation.UPSERT, PublishMode.REALTIME, TestRules.NOW);
    }

    @Test
    void ack_publishes_the_new_version_and_supersedes_the_old_one() {
        when(publisher.upsert(approved, "VA:rul_A:2")).thenReturn(new PublishOutcome.Acked("dpe-7781"));
        RulePublication pub = pending();

        worker.process(pub);

        assertThat(pub.getStatus()).isEqualTo(PublicationStatus.ACKED);
        assertThat(pub.getDpeReference()).isEqualTo("dpe-7781");
        assertThat(approved.getStatus()).isEqualTo(RuleStatus.PUBLISHED);
        assertThat(previousLive.getStatus()).isEqualTo(RuleStatus.SUPERSEDED);
        verify(events).publishEvent(new RulesChangedEvent("VA", "rul_A"));
    }

    @Test
    void transient_failure_is_retried_later_with_the_same_key() {
        when(publisher.upsert(any(), anyString())).thenReturn(new PublishOutcome.Retryable("UNAVAILABLE"));
        RulePublication pub = pending();

        worker.process(pub);

        assertThat(pub.getStatus()).isEqualTo(PublicationStatus.PENDING);
        assertThat(pub.getAttempts()).isEqualTo(1);
        assertThat(pub.getNextAttemptAt()).isAfter(TestRules.NOW);
        assertThat(approved.getStatus()).isEqualTo(RuleStatus.APPROVED);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void gives_up_after_max_attempts() {
        when(publisher.upsert(any(), anyString())).thenReturn(new PublishOutcome.Retryable("DEADLINE_EXCEEDED"));
        RulePublication pub = pending();

        worker.process(pub);
        worker.process(pub);
        worker.process(pub);

        assertThat(pub.getStatus()).isEqualTo(PublicationStatus.FAILED);
        assertThat(pub.getAttempts()).isEqualTo(3);
        verify(audit).ruleAction(eq(approved), eq("PUBLISH_FAILED"), eq("system"), any(), any());
    }

    @Test
    void rejection_fails_at_once_and_keeps_the_live_version() {
        when(publisher.upsert(any(), anyString())).thenReturn(new PublishOutcome.Rejected("INVALID_ARGUMENT: bad"));
        RulePublication pub = pending();

        worker.process(pub);

        assertThat(pub.getStatus()).isEqualTo(PublicationStatus.FAILED);
        assertThat(previousLive.getStatus()).isEqualTo(RuleStatus.PUBLISHED);
    }

    @Test
    void backoff_grows_exponentially_and_is_capped() {
        Duration base = Duration.ofSeconds(30);
        assertThat(Backoff.delay(base, 0)).isBetween(Duration.ofSeconds(24), Duration.ofSeconds(36));
        assertThat(Backoff.delay(base, 3)).isBetween(Duration.ofSeconds(192), Duration.ofSeconds(288));
        assertThat(Backoff.delay(base, 15)).isLessThanOrEqualTo(Duration.ofMinutes(36));
    }
}
