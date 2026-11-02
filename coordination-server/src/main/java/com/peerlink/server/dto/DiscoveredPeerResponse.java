package com.peerlink.server.dto;

import com.peerlink.server.entity.Peer;
import java.time.Instant;
import java.util.UUID;

/**
 * What other users learn about an online peer. Network address and port are deliberately left
 * out: they will only be handed to a sender once a transfer has been authorized.
 */
public record DiscoveredPeerResponse(UUID id, String ownerUsername, String ownerDisplayName, String deviceName,
                                     Instant lastHeartbeatAt) {

    public static DiscoveredPeerResponse from(Peer peer) {
        return new DiscoveredPeerResponse(peer.getId(), peer.getUser().getUsername(),
                peer.getUser().getDisplayName(), peer.getDeviceName(), peer.getLastHeartbeatAt());
    }
}
