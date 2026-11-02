package com.peerlink.server.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A single-use ticket for one direct connection, returned only to the source peer's agent. It is
 * the only response that reveals a target's network address. Deliberately flat, because the peer
 * agent parses it without a JSON library. {@link #toString()} masks the secrets.
 */
public record ConnectionTicketResponse(UUID ticketId, String ticket, UUID sourcePeerId, UUID targetPeerId,
                                       String targetHost, int targetPort, Instant expiresAt, String responderProof) {

    @Override
    public String toString() {
        return "ConnectionTicketResponse[ticketId=" + ticketId + ", sourcePeerId=" + sourcePeerId + ", targetPeerId="
                + targetPeerId + ", ticket=****, responderProof=****, expiresAt=" + expiresAt + "]";
    }
}
