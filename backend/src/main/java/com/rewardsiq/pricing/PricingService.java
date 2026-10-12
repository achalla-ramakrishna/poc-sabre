package com.rewardsiq.pricing;

import com.rewardsiq.rule.RedemptionRule;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;

/** UC-08: price a batch of offers in points from the in-memory index. */
@Service
public class PricingService {

    private final RulesCache cache;
    private final PointsCalculator calculator;
    private final Clock clock;

    public PricingService(RulesCache cache, PointsCalculator calculator, Clock clock) {
        this.cache = cache;
        this.calculator = calculator;
        this.clock = clock;
    }

    public List<PointsResult> price(String airline, List<OfferQuery> offers, OptionalLong memberBalance) {
        return price(cache.current(), airline, offers, memberBalance);
    }

    List<PointsResult> price(RuleIndex index, String airline, List<OfferQuery> offers, OptionalLong memberBalance) {
        LocalDate today = LocalDate.now(clock);
        return offers.stream().map(offer -> {
            List<RedemptionRule> best = index.bestMatches(airline, offer, today);
            return best.isEmpty()
                    ? PointsResult.cashOnly(offer.offerId())   // BR-11
                    : calculator.price(best.get(0), offer, memberBalance);
        }).toList();
    }
}
