package com.rewardsiq.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.rewardsiq.TestRules;
import com.rewardsiq.rule.Cabin;
import com.rewardsiq.rule.RedemptionRule;
import com.rewardsiq.rule.Scope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuleIndexTest {

    private static final LocalDate SALE_DATE = LocalDate.parse("2026-11-01");

    private static OfferQuery offer(String origin, String destination, Cabin cabin, String brand) {
        return new OfferQuery("of", origin, destination, cabin, brand, "", "AU", new BigDecimal("900"), "AUD");
    }

    private final RedemptionRule specific = TestRules.published("rul_SPECIFIC", TestRules.sydPerEconomyChoice());
    private final RedemptionRule anyRouteEconomy = TestRules.published("rul_ANY",
            new Scope(Set.of(), Set.of(), Set.of(Cabin.ECONOMY), Set.of(), Set.of(), Set.of()));
    private final RuleIndex index = RuleIndex.of(List.of(specific, anyRouteEconomy));

    @Test
    void most_specific_rule_wins() {
        assertThat(index.bestMatches("VA", offer("SYD", "PER", Cabin.ECONOMY, "CHOICE"), SALE_DATE))
                .containsExactly(specific);
    }

    @Test
    void wildcard_bucket_catches_other_routes() {
        assertThat(index.bestMatches("VA", offer("BNE", "MEL", Cabin.ECONOMY, "LITE"), SALE_DATE))
                .containsExactly(anyRouteEconomy);
    }

    @Test
    void no_match_for_other_cabin_or_airline() {
        assertThat(index.bestMatches("VA", offer("SYD", "PER", Cabin.BUSINESS, "BUSINESS"), SALE_DATE)).isEmpty();
        assertThat(index.bestMatches("NZ", offer("SYD", "PER", Cabin.ECONOMY, "CHOICE"), SALE_DATE)).isEmpty();
    }

    @Test
    void rules_outside_their_effective_dates_are_ignored() {
        assertThat(index.bestMatches("VA", offer("SYD", "PER", Cabin.ECONOMY, "CHOICE"),
                LocalDate.parse("2027-04-01"))).isEmpty();
    }

    @Test
    void equal_specificity_ties_are_all_returned() {
        RedemptionRule twin = TestRules.published("rul_TWIN", TestRules.sydPerEconomyChoice());
        assertThat(RuleIndex.of(List.of(specific, twin))
                .bestMatches("VA", offer("SYD", "PER", Cabin.ECONOMY, "CHOICE"), SALE_DATE)).hasSize(2);
    }
}
