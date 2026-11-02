package com.peerlink.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Direct-connection policy, bound from {@code peerlink.connections.*}.
 *
 * @param ticketTtl how long a connection ticket can be redeemed after it is issued
 */
@ConfigurationProperties(prefix = "peerlink.connections")
public record ConnectionProperties(Duration ticketTtl) {

    static final Duration MIN_TTL = Duration.ofSeconds(5);
    static final Duration MAX_TTL = Duration.ofMinutes(10);

    public ConnectionProperties {
        ticketTtl = ticketTtl == null ? Duration.ofSeconds(60) : ticketTtl;
        if (ticketTtl.compareTo(MIN_TTL) < 0 || ticketTtl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("peerlink.connections.ticket-ttl must be between "
                    + MIN_TTL + " and " + MAX_TTL + ", got " + ticketTtl);
        }
    }
}
