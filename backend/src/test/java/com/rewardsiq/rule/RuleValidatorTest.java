package com.rewardsiq.rule;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rewardsiq.TestRules;
import com.rewardsiq.common.error.RuleValidationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuleValidatorTest {

    private final RuleValidator validator = new RuleValidator();
    private static final LocalDate FROM = LocalDate.parse("2026-11-01");

    @Test
    void valid_rule_passes() {
        assertThatCode(() -> validator.validateContent("SYD-PER", TestRules.sydPerEconomyChoice(),
                List.of(TestRules.choiceRate()), new Limits(70, 5000), FROM, null)).doesNotThrowAnyException();
    }

    @Test
    void reports_every_broken_field() {
        Scope badScope = new Scope(Set.of("Sydney"), Set.of("PER"), Set.of(), Set.of("CHOICE"), Set.of(), Set.of("AUS"));
        List<Rate> badRates = List.of(new Rate("LITE", new BigDecimal("-1"), "aud", 7));

        assertThatThrownBy(() -> validator.validateContent("", badScope, badRates, new Limits(120, -1), FROM,
                FROM.minusDays(1)))
                .isInstanceOf(RuleValidationException.class)
                .extracting("fieldErrors")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKeys("name", "scope.origins", "scope.pointsOfSale", "rates[0].pointsPerUnit",
                        "rates[0].currency", "rates[0].roundingStep", "rates[0].fareBrand",
                        "limits.maxRedeemPercent", "limits.minPoints", "effectiveTo");
    }

    @Test
    void duplicate_fare_brand_rates_are_rejected() {
        List<Rate> dup = List.of(TestRules.choiceRate(), TestRules.choiceRate());
        assertThatThrownBy(() -> validator.validateContent("x", TestRules.sydPerEconomyChoice(), dup,
                new Limits(70, 0), FROM, null)).isInstanceOf(RuleValidationException.class);
    }

    @Test
    void conflicting_live_rule_with_same_specificity_blocks_approval() {
        RedemptionRule live = TestRules.published("rul_LIVE", TestRules.sydPerEconomyChoice());
        RedemptionRule candidate = TestRules.draft("rul_NEW", TestRules.sydPerEconomyChoice());

        assertThatThrownBy(() -> validator.checkConflicts(candidate, List.of(live)))
                .isInstanceOf(RuleValidationException.class)
                .extracting("errorCode").isEqualTo("RULE_CONFLICT");
    }

    @Test
    void more_specific_or_non_overlapping_rules_do_not_conflict() {
        RedemptionRule live = TestRules.published("rul_LIVE", TestRules.sydPerEconomyChoice());
        RedemptionRule broader = TestRules.draft("rul_BROAD",
                new Scope(Set.of(), Set.of(), Set.of(Cabin.ECONOMY), Set.of(), Set.of(), Set.of()));
        RedemptionRule otherRoute = TestRules.draft("rul_MEL",
                new Scope(Set.of("SYD"), Set.of("MEL"), Set.of(Cabin.ECONOMY), Set.of("CHOICE"), Set.of(), Set.of("AU")));
        RedemptionRule nextVersionOfSame = TestRules.draft("rul_LIVE", TestRules.sydPerEconomyChoice());

        assertThatCode(() -> {
            validator.checkConflicts(broader, List.of(live));
            validator.checkConflicts(otherRoute, List.of(live));
            validator.checkConflicts(nextVersionOfSame, List.of(live));
        }).doesNotThrowAnyException();
    }
}
