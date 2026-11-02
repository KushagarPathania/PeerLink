package com.peerlink.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Heartbeat policy, bound from {@code peerlink.peers.*}.
 *
 * @param heartbeatInterval how often agents are told to send a heartbeat
 * @param staleTimeout      a peer whose last heartbeat is older than this is offline
 * @param sweepInterval     how often stale peers are marked OFFLINE in the database
 */
@ConfigurationProperties(prefix = "peerlink.peers")
public record PeerPresenceProperties(Duration heartbeatInterval, Duration staleTimeout, Duration sweepInterval) {

    public PeerPresenceProperties {
        heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(15) : heartbeatInterval;
        staleTimeout = staleTimeout == null ? Duration.ofSeconds(60) : staleTimeout;
        sweepInterval = sweepInterval == null ? Duration.ofSeconds(15) : sweepInterval;
        requirePositive("heartbeat-interval", heartbeatInterval);
        requirePositive("stale-timeout", staleTimeout);
        requirePositive("sweep-interval", sweepInterval);
        // One missed heartbeat must not take a peer offline.
        if (staleTimeout.compareTo(heartbeatInterval.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException("peerlink.peers.stale-timeout (" + staleTimeout
                    + ") must be at least twice heartbeat-interval (" + heartbeatInterval + ")");
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("peerlink.peers." + name + " must be positive, got " + value);
        }
    }
}
