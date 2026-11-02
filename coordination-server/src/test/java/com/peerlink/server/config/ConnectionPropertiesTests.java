package com.peerlink.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ConnectionPropertiesTests {

    @Test
    void defaultsToOneMinute() {
        assertThat(new ConnectionProperties(null).ticketTtl()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void rejectsTtlOutsideBounds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ConnectionProperties(Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new ConnectionProperties(Duration.ofHours(1)));
    }
}
