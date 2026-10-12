package com.rewardsiq.publish;

import com.rewardsiq.common.config.RewardsIqProperties.PublishMode;
import com.rewardsiq.rule.RedemptionRule;

/**
 * Strategy for delivering rules to DPE (Open/Closed: Plan B is another implementation, chosen by
 * the rewards.publish.mode setting, not by if/else in the worker).
 */
public interface RulePublisher {

    PublishMode mode();

    PublishOutcome upsert(RedemptionRule rule, String idempotencyKey);

    PublishOutcome retire(RedemptionRule rule, String idempotencyKey);
}
