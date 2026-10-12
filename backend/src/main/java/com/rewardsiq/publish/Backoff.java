package com.rewardsiq.publish;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Exponential backoff with +/-20% jitter: base, 2x base, 4x base ... capped at 30 minutes. */
final class Backoff {

    static final Duration MAX = Duration.ofMinutes(30);

    private Backoff() {
    }

    static Duration delay(Duration base, int attemptsSoFar) {
        long millis = base.toMillis() * (1L << Math.min(attemptsSoFar, 20));
        millis = Math.min(millis, MAX.toMillis());
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return Duration.ofMillis((long) (millis * jitter));
    }
}
