package com.peerlink.server.dto;

import java.util.UUID;

/**
 * The server-verified identity of the connecting (source) peer, the fingerprint of the TLS
 * certificate it must present, the proof the target must send back, and, for a transfer ticket,
 * exactly which file transfer is authorized. Flat for the dependency-free agent parser;
 * {@link #toString()} masks the proof.
 */
public record TicketRedemptionResponse(UUID ticketId, UUID sourcePeerId, String sourceDeviceName,
                                       String sourceOwnerUsername, String responderProof,
                                       String initiatorCertificateSha256, UUID transferId, UUID sharedFileId,
                                       String fileName, Long fileSizeBytes, String sha256Checksum,
                                       Integer chunkSizeBytes) {

    @Override
    public String toString() {
        return "TicketRedemptionResponse[ticketId=" + ticketId + ", sourcePeerId=" + sourcePeerId + ", transferId="
                + transferId + ", responderProof=****]";
    }
}
