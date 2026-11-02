package com.peerlink.agent.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffPolicyTests {

    private final BackoffPolicy policy = BackoffPolicy.standard();

    @Test
    void doublesUntilTheCap() {
        assertThat(policy.delayFor(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delayFor(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayFor(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delayFor(6)).isEqualTo(Duration.ofSeconds(32));
        assertThat(policy.delayFor(7)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void neverExceedsTheCapOrOverflows() {
        for (int attempt = 1; attempt < 10_000; attempt += 97) {
            assertThat(policy.delayFor(attempt)).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(60));
        }
        assertThat(policy.delayFor(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void rejectsInvalidBounds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new BackoffPolicy(Duration.ZERO, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1)));
    }
}
