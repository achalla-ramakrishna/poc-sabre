package com.rewardsiq.pricing;

/**
 * @param pointsUsable 0 when no member balance was supplied, or when the cap is below the rule's minimum
 */
public record PointsResult(String offerId, boolean cashOnly, long pointsRequired, long pointsUsable,
                           String ruleId, int ruleVersion) {

    public static PointsResult cashOnly(String offerId) {
        return new PointsResult(offerId, true, 0, 0, null, 0);
    }
}
