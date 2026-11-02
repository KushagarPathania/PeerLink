package com.peerlink.server.dto;

import com.peerlink.server.entity.PeerStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Acknowledges a heartbeat. {@code observedAddress} is the address the server saw the agent
 * connect from; agents log it so a loopback or proxy address is easy to spot.
 */
public record HeartbeatResponse(UUID peerId, PeerStatus status, Instant serverTime, String observedAddress,
                                long heartbeatIntervalSeconds, long staleTimeoutSeconds) {
}
