package com.rewardsiq.rule;

import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** ULID-style ids ("rul_01JA3K7Q2M..."): time-ordered, so ids sort roughly by creation. */
@Component
public class RuleIdGenerator {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public RuleIdGenerator(Clock clock) {
        this.clock = clock;
    }

    public String next() {
        StringBuilder sb = new StringBuilder("rul_");
        long time = clock.millis();
        char[] timePart = new char[10];
        for (int i = 9; i >= 0; i--) {
            timePart[i] = CROCKFORD[(int) (time & 31)];
            time >>>= 5;
        }
        sb.append(timePart);
        for (int i = 0; i < 16; i++) {
            sb.append(CROCKFORD[random.nextInt(32)]);
        }
        return sb.toString();
    }
}
