package com.rewardsiq.rule;

/**
 * @param maxRedeemPercent share of the member's balance usable on one offer, 0-100 (BR-02)
 * @param minPoints        smallest redemption allowed; below it the offer can't be paid in points
 */
public record Limits(int maxRedeemPercent, long minPoints) {
}
