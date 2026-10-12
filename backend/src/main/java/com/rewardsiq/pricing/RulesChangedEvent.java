package com.rewardsiq.pricing;

/** Published after a rule version goes live or is retired, so the pricing cache reloads at once. */
public record RulesChangedEvent(String airline, String ruleId) {
}
