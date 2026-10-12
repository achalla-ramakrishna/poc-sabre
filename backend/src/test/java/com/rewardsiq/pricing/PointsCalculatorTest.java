package com.rewardsiq.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.rewardsiq.TestRules;
import com.rewardsiq.rule.Cabin;
import com.rewardsiq.rule.Limits;
import com.rewardsiq.rule.Rate;
import com.rewardsiq.rule.RedemptionRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class PointsCalculatorTest {

    private final PointsCalculator calculator = new PointsCalculator();
    private final RedemptionRule rule = TestRules.published("rul_A", TestRules.sydPerEconomyChoice());

    private static OfferQuery offer(String amount, String brand, String currency) {
        return new OfferQuery("of-1", "SYD", "PER", Cabin.ECONOMY, brand, null, "AU", new BigDecimal(amount), currency);
    }

    @Test
    void spec_example_900_aud_is_43100_points_all_usable() {
        PointsResult r = calculator.price(rule, offer("900.00", "CHOICE", "AUD"), OptionalLong.of(100_000));

        assertThat(r.cashOnly()).isFalse();
        assertThat(r.pointsRequired()).isEqualTo(43_100);   // 900 x 47.8 = 43,020 -> up to 43,100
        assertThat(r.pointsUsable()).isEqualTo(43_100);
        assertThat(r.ruleId()).isEqualTo("rul_A");
    }

    @Test
    void cap_limits_usable_points() {
        PointsResult r = calculator.price(rule, offer("1800.00", "CHOICE", "AUD"), OptionalLong.of(100_000));

        assertThat(r.pointsRequired()).isEqualTo(86_100);
        assertThat(r.pointsUsable()).isEqualTo(70_000);     // 70% of 100,000
    }

    @Test
    void no_balance_means_required_only() {
        assertThat(calculator.price(rule, offer("900", "CHOICE", "AUD"), OptionalLong.empty()).pointsUsable()).isZero();
    }

    @Test
    void below_minimum_points_nothing_is_usable() {
        // 70% of 5,000 = 3,500 < minPoints 5,000
        assertThat(calculator.price(rule, offer("900", "CHOICE", "AUD"), OptionalLong.of(5_000)).pointsUsable())
                .isZero();
    }

    @Test
    void currency_mismatch_is_cash_only() {
        assertThat(calculator.price(rule, offer("900", "CHOICE", "NZD"), OptionalLong.of(100_000)).cashOnly()).isTrue();
    }

    @Test
    void falls_back_to_default_rate_when_fare_brand_has_none() {
        RedemptionRule withDefault = new RedemptionRule("VA", "rul_D", 1, "Default", TestRules.sydPerEconomyChoice(),
                List.of(new Rate(null, new BigDecimal("50"), "AUD", 10), TestRules.choiceRate()),
                new Limits(100, 0), LocalDate.parse("2026-01-01"), null, "u", TestRules.NOW);

        assertThat(calculator.price(withDefault, offer("99.99", "LITE", "AUD"), OptionalLong.empty()).pointsRequired())
                .isEqualTo(5_000);  // 4,999.5 -> up to 5,000 (step 10)
    }

    @Test
    void rounding_helpers_go_up_for_required_and_down_for_usable() {
        assertThat(PointsCalculator.pointsRequired(new BigDecimal("100"), new BigDecimal("1.01"), 100)).isEqualTo(200);
        assertThat(PointsCalculator.pointsUsable(1_000_000, 12_345, 70, 100, 0)).isEqualTo(8_600); // 8,641.5 -> 8,600
        assertThat(PointsCalculator.pointsUsable(1_000, 10_000, 0, 100, 0)).isZero();
    }
}
