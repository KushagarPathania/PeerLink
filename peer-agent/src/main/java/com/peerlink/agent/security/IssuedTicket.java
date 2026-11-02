package com.peerlink.agent.security;

import java.time.Instant;
import java.util.UUID;

/**
 * Authorization for one connection to {@code targetPeerId}, obtained from the coordination server.
 * {@code responderProof} is what the genuine target will echo in its handshake reply.
 * {@link #toString()} hides both secrets.
 */
public record IssuedTicket(UUID ticketId, String ticket, UUID targetPeerId, String targetHost, int targetPort,
                           Instant expiresAt, String responderProof) {

    @Override
    public String toString() {
        return "IssuedTicket[ticketId=" + ticketId + ", targetPeerId=" + targetPeerId + ", target=" + targetHost
                + ":" + targetPort + ", expiresAt=" + expiresAt + ", ticket=****, responderProof=****]";
    }
}
