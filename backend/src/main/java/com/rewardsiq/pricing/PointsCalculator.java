package com.rewardsiq.pricing;

import com.rewardsiq.rule.Rate;
import com.rewardsiq.rule.RedemptionRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.stereotype.Component;

/**
 * BR-03 and BR-04. Pure function of (rule, offer, balance): no I/O, so it's safe on the hot path
 * and trivially unit-testable.
 */
@Component
public class PointsCalculator {

    public PointsResult price(RedemptionRule rule, OfferQuery offer, OptionalLong memberBalance) {
        Optional<Rate> rate = rateFor(rule, offer.fareBrand());
        if (rate.isEmpty() || !rate.get().currency().equals(offer.currency())) {
            return PointsResult.cashOnly(offer.offerId());
        }
        long step = rate.get().roundingStep();
        long required = pointsRequired(offer.fareAmount(), rate.get().pointsPerUnit(), step);
        long usable = memberBalance.isPresent()
                ? pointsUsable(required, memberBalance.getAsLong(), rule.getLimits().maxRedeemPercent(), step,
                rule.getLimits().minPoints())
                : 0;
        return new PointsResult(offer.offerId(), false, required, usable, rule.getRuleId(), rule.getVersion());
    }

    /** BR-03: fare x points-per-unit, rounded UP to the step. */
    static long pointsRequired(BigDecimal fare, BigDecimal pointsPerUnit, long step) {
        BigDecimal raw = fare.multiply(pointsPerUnit);
        return raw.divide(BigDecimal.valueOf(step), 0, RoundingMode.CEILING)
                .multiply(BigDecimal.valueOf(step))
                .longValueExact();
    }

    /** BR-04: min(required, balance x cap%), rounded DOWN to the step; below the rule minimum means none. */
    static long pointsUsable(long required, long balance, int maxRedeemPercent, long step, long minPoints) {
        if (balance <= 0 || maxRedeemPercent <= 0) {
            return 0;
        }
        long cap = BigDecimal.valueOf(balance)
                .multiply(BigDecimal.valueOf(maxRedeemPercent))
                .divide(BigDecimal.valueOf(100L * step), 0, RoundingMode.FLOOR)
                .multiply(BigDecimal.valueOf(step))
                .longValueExact();
        long usable = Math.min(required, cap);
        return usable < minPoints ? 0 : usable;
    }

    /** The rate for the offer's fare brand, else the rule's default (blank fare brand) rate. */
    static Optional<Rate> rateFor(RedemptionRule rule, String fareBrand) {
        return rule.getRates().stream()
                .filter(r -> !r.isDefault() && r.fareBrand().equals(fareBrand))
                .findFirst()
                .or(() -> rule.getRates().stream().filter(Rate::isDefault).findFirst());
    }
}
