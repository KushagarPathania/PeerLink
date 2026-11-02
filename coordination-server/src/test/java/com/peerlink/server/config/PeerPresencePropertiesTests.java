package com.peerlink.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PeerPresencePropertiesTests {

    @Test
    void appliesDefaults() {
        PeerPresenceProperties properties = new PeerPresenceProperties(null, null, null);

        assertThat(properties.heartbeatInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.staleTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.sweepInterval()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void rejectsTimeoutShorterThanTwoHeartbeats() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PeerPresenceProperties(Duration.ofSeconds(30), Duration.ofSeconds(45), Duration.ofSeconds(15)));
    }

    @Test
    void rejectsNonPositiveDurations() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PeerPresenceProperties(Duration.ZERO, Duration.ofSeconds(60), Duration.ofSeconds(15)));
    }
}
