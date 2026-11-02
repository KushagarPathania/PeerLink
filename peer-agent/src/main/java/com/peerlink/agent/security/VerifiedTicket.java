package com.peerlink.agent.security;

import java.util.UUID;

/**
 * Result of redeeming a presented ticket: who the server says the connecting peer is, and the
 * proof to send back to it. {@link #toString()} hides the proof.
 */
public record VerifiedTicket(UUID ticketId, UUID sourcePeerId, String sourceDeviceName, String sourceOwnerUsername,
                             String responderProof) {

    @Override
    public String toString() {
        return "VerifiedTicket[ticketId=" + ticketId + ", sourcePeerId=" + sourcePeerId + ", responderProof=****]";
    }
}
