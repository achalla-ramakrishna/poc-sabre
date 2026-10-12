package com.rewardsiq;

import com.rewardsiq.rule.Cabin;
import com.rewardsiq.rule.Limits;
import com.rewardsiq.rule.Rate;
import com.rewardsiq.rule.RedemptionRule;
import com.rewardsiq.rule.Scope;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Test data matching the spec's running example: SYD-PER Economy Choice, 47.8 pts/AUD, 70% cap. */
public final class TestRules {

    public static final Instant NOW = Instant.parse("2026-10-09T05:30:00Z");

    private TestRules() {
    }

    public static Scope sydPerEconomyChoice() {
        return new Scope(Set.of("SYD"), Set.of("PER"), Set.of(Cabin.ECONOMY), Set.of("CHOICE"), Set.of(), Set.of("AU"));
    }

    public static Rate choiceRate() {
        return new Rate("CHOICE", new BigDecimal("47.8"), "AUD", 100);
    }

    public static RedemptionRule draft(String ruleId, Scope scope) {
        return new RedemptionRule("VA", ruleId, 1, "Rule " + ruleId, scope, List.of(choiceRate()),
                new Limits(70, 5000), LocalDate.parse("2026-10-01"), LocalDate.parse("2027-03-31"), "u-analyst", NOW);
    }

    public static RedemptionRule published(String ruleId, Scope scope) {
        RedemptionRule rule = draft(ruleId, scope);
        rule.submit("u-analyst");
        rule.approve("u-approver", NOW);
        rule.markPublished(NOW);
        return rule;
    }

    public static RedemptionRule withRevision(RedemptionRule rule, long revision) {
        try {
            Field f = RedemptionRule.class.getDeclaredField("revision");
            f.setAccessible(true);
            f.set(rule, revision);
            return rule;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
