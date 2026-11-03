package com.peerlink.agent.security;

import java.util.UUID;

/**
 * Result of redeeming a presented ticket: who the server says the connecting peer is, the
 * fingerprint of the TLS certificate it must have presented, the proof to send back, and (for a
 * transfer ticket) the authorized transfer. {@link #toString()} hides the proof.
 */
public record VerifiedTicket(UUID ticketId, UUID sourcePeerId, String sourceDeviceName, String sourceOwnerUsername,
                             String responderProof, String initiatorCertificateSha256, TransferGrant transfer) {

    @Override
    public String toString() {
        return "VerifiedTicket[ticketId=" + ticketId + ", sourcePeerId=" + sourcePeerId + ", transfer="
                + (transfer == null ? null : transfer.transferId()) + ", responderProof=****]";
    }
}
