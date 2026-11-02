package com.peerlink.server.dto;

import java.util.UUID;

/**
 * The server-verified identity of the connecting (source) peer, plus the proof the target must
 * send back to it. Flat for the dependency-free agent parser; {@link #toString()} masks the proof.
 */
public record TicketRedemptionResponse(UUID ticketId, UUID sourcePeerId, String sourceDeviceName,
                                       String sourceOwnerUsername, String responderProof) {

    @Override
    public String toString() {
        return "TicketRedemptionResponse[ticketId=" + ticketId + ", sourcePeerId=" + sourcePeerId
                + ", responderProof=****]";
    }
}
