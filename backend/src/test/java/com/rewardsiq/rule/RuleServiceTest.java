package com.rewardsiq.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rewardsiq.TestRules;
import com.rewardsiq.audit.AuditService;
import com.rewardsiq.common.config.RewardsIqProperties;
import com.rewardsiq.common.config.RewardsIqProperties.PublishMode;
import com.rewardsiq.common.error.PreconditionFailedException;
import com.rewardsiq.publish.Operation;
import com.rewardsiq.publish.PublicationRepository;
import com.rewardsiq.publish.RulePublication;
import com.rewardsiq.rule.RuleDtos.RateDto;
import com.rewardsiq.rule.RuleDtos.RuleRequest;
import com.rewardsiq.security.CurrentUser;
import com.rewardsiq.security.Role;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RuleServiceTest {

    private static final CurrentUser ANALYST = new CurrentUser("u-analyst", "VA", Set.of(Role.ANALYST));
    private static final CurrentUser APPROVER = new CurrentUser("u-approver", "VA", Set.of(Role.APPROVER));

    @Mock RuleRepository rules;
    @Mock PublicationRepository publications;
    @Mock AuditService audit;
    @Mock RuleIdGenerator ids;

    private RuleService service;

    @BeforeEach
    void setUp() {
        var props = new RewardsIqProperties(
                new RewardsIqProperties.Publish(PublishMode.REALTIME, Duration.ofSeconds(5), 6, Duration.ofSeconds(30), 50),
                null, null, null, null, null);
        service = new RuleService(rules, publications, new RuleValidator(), ids, audit, props,
                Clock.fixed(TestRules.NOW, ZoneOffset.UTC));
    }

    private static RuleRequest request(String name) {
        return new RuleRequest(name, TestRules.sydPerEconomyChoice(),
                List.of(new RateDto("CHOICE", new BigDecimal("47.8"), "AUD", 100)),
                new Limits(70, 5000), LocalDate.parse("2026-11-01"), LocalDate.parse("2027-03-31"));
    }

    @Test
    void create_saves_version_1_draft_for_the_users_airline() {
        when(ids.next()).thenReturn("rul_NEW");
        when(rules.insert(any(RedemptionRule.class))).thenAnswer(i -> i.getArgument(0));

        RedemptionRule rule = service.create(request("SYD-PER Economy Choice"), ANALYST);

        assertThat(rule.getAirline()).isEqualTo("VA");
        assertThat(rule.getVersion()).isEqualTo(1);
        assertThat(rule.getStatus()).isEqualTo(RuleStatus.DRAFT);
        verify(audit).ruleAction(eq(rule), eq("CREATE"), eq("u-analyst"), any(), any());
    }

    @Test
    void approve_queues_an_outbox_row_in_the_same_call() {
        RedemptionRule rule = TestRules.withRevision(TestRules.draft("rul_A", TestRules.sydPerEconomyChoice()), 3L);
        rule.submit("u-analyst");
        when(rules.findFirstByAirlineAndRuleIdOrderByVersionDesc("VA", "rul_A")).thenReturn(Optional.of(rule));
        when(rules.findByAirlineAndStatusIn(eq("VA"), any())).thenReturn(List.of());
        when(rules.save(rule)).thenReturn(rule);

        service.approve("rul_A", 3L, APPROVER);

        ArgumentCaptor<RulePublication> outbox = ArgumentCaptor.forClass(RulePublication.class);
        verify(publications).insert(outbox.capture());
        assertThat(outbox.getValue().getOperation()).isEqualTo(Operation.UPSERT);
        assertThat(outbox.getValue().getIdempotencyKey()).isEqualTo("VA:rul_A:1");
        assertThat(rule.getStatus()).isEqualTo(RuleStatus.APPROVED);
    }

    @Test
    void stale_if_match_is_rejected_before_anything_changes() {
        RedemptionRule rule = TestRules.withRevision(TestRules.draft("rul_A", TestRules.sydPerEconomyChoice()), 4L);
        when(rules.findFirstByAirlineAndRuleIdOrderByVersionDesc("VA", "rul_A")).thenReturn(Optional.of(rule));

        assertThatThrownBy(() -> service.submit("rul_A", 3L, ANALYST)).isInstanceOf(PreconditionFailedException.class);
        verify(rules, never()).save(any());
    }

    @Test
    void editing_a_published_rule_creates_the_next_draft_version() {
        RedemptionRule live = TestRules.withRevision(TestRules.published("rul_A", TestRules.sydPerEconomyChoice()), 7L);
        when(rules.findFirstByAirlineAndRuleIdOrderByVersionDesc("VA", "rul_A")).thenReturn(Optional.of(live));
        when(rules.existsByAirlineAndNameIgnoreCaseAndRuleIdNot(anyString(), anyString(), anyString())).thenReturn(false);
        when(rules.save(any(RedemptionRule.class))).thenAnswer(i -> i.getArgument(0));

        RedemptionRule saved = service.update("rul_A", request("Renamed"), 7L, ANALYST);

        assertThat(saved).isNotSameAs(live);
        assertThat(saved.getVersion()).isEqualTo(2);
        assertThat(saved.getStatus()).isEqualTo(RuleStatus.DRAFT);
        assertThat(live.getStatus()).isEqualTo(RuleStatus.PUBLISHED);
        verify(audit).ruleAction(eq(saved), eq("NEW_VERSION"), eq("u-analyst"), any(), any());
    }

    @Test
    void retire_needs_a_published_version() {
        when(rules.findByAirlineAndRuleIdOrderByVersionDesc("VA", "rul_A"))
                .thenReturn(List.of(TestRules.draft("rul_A", TestRules.sydPerEconomyChoice())));

        assertThatThrownBy(() -> service.retire("rul_A", APPROVER))
                .isInstanceOf(RuleStateException.class)
                .extracting("errorCode").isEqualTo("NOT_LIVE");
    }
}
