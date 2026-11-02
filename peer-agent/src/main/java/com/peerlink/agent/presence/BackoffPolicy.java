package com.peerlink.agent.presence;

import java.time.Duration;

/**
 * Exponential backoff with an upper bound: initial, 2x, 4x, ... but never longer than
 * {@code max}. The agent keeps retrying while the server is down, waiting at most {@code max}.
 */
public record BackoffPolicy(Duration initial, Duration max) {

    public BackoffPolicy {
        if (initial.isNegative() || initial.isZero() || max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("Backoff requires 0 < initial <= max");
        }
    }

    public static BackoffPolicy standard() {
        return new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60));
    }

    /** Delay before the next attempt after {@code consecutiveFailures} failures in a row (1-based). */
    public Duration delayFor(int consecutiveFailures) {
        int doublings = Math.min(Math.max(consecutiveFailures - 1, 0), 30); // 2^30 already exceeds any cap
        Duration delay = initial.multipliedBy(1L << doublings);
        return delay.compareTo(max) > 0 ? max : delay;
    }
}
