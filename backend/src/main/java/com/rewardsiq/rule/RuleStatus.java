package com.rewardsiq.rule;

/**
 * Lifecycle of one rule version.
 * <pre>
 * DRAFT -> PENDING_APPROVAL -> APPROVED -> PUBLISHED -> SUPERSEDED (a newer version published)
 *   ^            |                                  \-> RETIRE_PENDING -> RETIRED
 *   +-- reject --+
 * </pre>
 */
public enum RuleStatus {
    DRAFT, PENDING_APPROVAL, APPROVED, PUBLISHED, SUPERSEDED, RETIRE_PENDING, RETIRED;

    /** Versions that are (or are about to be) in force, and so take part in conflict checks. */
    public boolean isLiveOrQueued() {
        return this == APPROVED || this == PUBLISHED;
    }
}
