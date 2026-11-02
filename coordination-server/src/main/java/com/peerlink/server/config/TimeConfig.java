package com.peerlink.server.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({PeerPresenceProperties.class, ConnectionProperties.class})
public class TimeConfig {

    /** All server-side timestamps (heartbeats, cut-offs) come from this clock, never from clients. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
