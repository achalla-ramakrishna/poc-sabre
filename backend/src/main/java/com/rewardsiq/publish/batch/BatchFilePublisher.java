package com.rewardsiq.publish.batch;

import com.rewardsiq.common.config.RewardsIqProperties.PublishMode;
import com.rewardsiq.publish.PublishOutcome;
import com.rewardsiq.publish.RulePublisher;
import com.rewardsiq.rule.RedemptionRule;
import org.springframework.stereotype.Component;

/**
 * Plan B (UC-07): in BATCH mode a rule counts as delivered as soon as it is approved; the next
 * scheduled snapshot file carries it to the DPE team. Switch with rewards.publish.mode=BATCH.
 */
@Component
public class BatchFilePublisher implements RulePublisher {

    static final String REFERENCE = "batch-snapshot";

    @Override
    public PublishMode mode() {
        return PublishMode.BATCH;
    }

    @Override
    public PublishOutcome upsert(RedemptionRule rule, String idempotencyKey) {
        return new PublishOutcome.Acked(REFERENCE);
    }

    @Override
    public PublishOutcome retire(RedemptionRule rule, String idempotencyKey) {
        // Retired rules simply drop out of the next snapshot.
        return new PublishOutcome.Acked(REFERENCE);
    }
}
