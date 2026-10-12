package com.rewardsiq.publish;

/**
 * What happened when we tried to deliver a rule version. Sealed: the worker's switch must handle
 * every case, and the compiler checks it.
 */
public sealed interface PublishOutcome {

    /** DPE applied it (or had already applied this idempotency key). */
    record Acked(String reference) implements PublishOutcome {
    }

    /** DPE refused the content (4xx-like). Retrying won't help. */
    record Rejected(String reason) implements PublishOutcome {
    }

    /** Timeout, outage, overload. Retry later with the same idempotency key. */
    record Retryable(String reason) implements PublishOutcome {
    }
}
