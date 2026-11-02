package com.peerlink.server.dto;

import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.PeerStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * An owner's view of one of their own peers. {@code status} is the effective status: a peer whose
 * heartbeats have stopped is reported OFFLINE even if the database row has not been updated yet.
 * {@code hostAddress} is the address the server last observed, or {@code null} before the first
 * heartbeat. The agent credential itself is never included.
 */
public record PeerResponse(UUID id, String ownerUsername, String deviceName, String hostAddress, int listeningPort,
                           PeerStatus status, Instant lastHeartbeatAt, boolean agentCredentialActive,
                           Instant agentCredentialIssuedAt) {

    public static PeerResponse from(Peer peer, Instant now, Duration heartbeatTimeout) {
        PeerStatus effectiveStatus = peer.isOnline(now, heartbeatTimeout) ? PeerStatus.ONLINE : PeerStatus.OFFLINE;
        String address = peer.getHostAddress() == null ? null : peer.getHostAddress().getHostAddress();
        return new PeerResponse(peer.getId(), peer.getUser().getUsername(), peer.getDeviceName(), address,
                peer.getListeningPort(), effectiveStatus, peer.getLastHeartbeatAt(), peer.hasActiveCredential(),
                peer.getCredentialIssuedAt());
    }
}
