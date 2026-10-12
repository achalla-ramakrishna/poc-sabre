package com.rewardsiq.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rewardsiq.TestRules;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RedemptionRuleTest {

    @Test
    void happy_path_lifecycle() {
        RedemptionRule rule = TestRules.draft("rul_A", TestRules.sydPerEconomyChoice());
        rule.submit("analyst");
        rule.approve("approver", TestRules.NOW);
        rule.markPublished(TestRules.NOW);

        assertThat(rule.getStatus()).isEqualTo(RuleStatus.PUBLISHED);
        assertThat(rule.getSpecificity()).isEqualTo(5);
        assertThat(rule.idempotencyKey()).isEqualTo("VA:rul_A:1");
    }

    @Test
    void submitter_cannot_approve_their_own_rule() {
        RedemptionRule rule = TestRules.draft("rul_A", TestRules.sydPerEconomyChoice());
        rule.submit("same-person");

        assertThatThrownBy(() -> rule.approve("same-person", TestRules.NOW))
                .isInstanceOf(RuleStateException.class)
                .extracting("errorCode").isEqualTo("SELF_APPROVAL");
    }

    @Test
    void only_drafts_can_be_edited() {
        RedemptionRule rule = TestRules.published("rul_A", TestRules.sydPerEconomyChoice());
        assertThatThrownBy(() -> rule.edit("x", rule.getScope(), rule.getRates(), rule.getLimits(),
                LocalDate.parse("2026-01-01"), null)).isInstanceOf(RuleStateException.class);
    }

    @Test
    void next_version_is_a_draft_copy() {
        RedemptionRule live = TestRules.published("rul_A", TestRules.sydPerEconomyChoice());
        RedemptionRule next = live.nextVersion("analyst", TestRules.NOW);

        assertThat(next.getVersion()).isEqualTo(2);
        assertThat(next.getStatus()).isEqualTo(RuleStatus.DRAFT);
        assertThat(next.getScope()).isEqualTo(live.getScope());
        assertThat(live.getStatus()).isEqualTo(RuleStatus.PUBLISHED);
    }

    @Test
    void reject_returns_to_draft_with_reason() {
        RedemptionRule rule = TestRules.draft("rul_A", TestRules.sydPerEconomyChoice());
        rule.submit("analyst");
        rule.reject("Rate too generous for peak dates");

        assertThat(rule.getStatus()).isEqualTo(RuleStatus.DRAFT);
        assertThat(rule.getRejectionReason()).contains("peak");
    }

    @Test
    void effective_dates_are_inclusive_and_open_ended_works() {
        RedemptionRule rule = TestRules.draft("rul_A", TestRules.sydPerEconomyChoice());
        assertThat(rule.isEffectiveOn(LocalDate.parse("2026-10-01"))).isTrue();
        assertThat(rule.isEffectiveOn(LocalDate.parse("2027-03-31"))).isTrue();
        assertThat(rule.isEffectiveOn(LocalDate.parse("2027-04-01"))).isFalse();
    }
}
