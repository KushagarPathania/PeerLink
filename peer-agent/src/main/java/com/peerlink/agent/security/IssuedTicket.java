package com.peerlink.agent.security;

import java.time.Instant;
import java.util.UUID;

/**
 * Authorization for one connection to {@code targetPeerId}, obtained from the coordination server.
 * {@code targetCertificateSha256} pins the TLS certificate the genuine target presents;
 * {@code responderProof} is what it will echo in its handshake reply. {@code transferId} is set
 * when the ticket authorizes fetching one specific file transfer. {@link #toString()} hides the secrets.
 */
public record IssuedTicket(UUID ticketId, String ticket, UUID targetPeerId, String targetHost, int targetPort,
                           Instant expiresAt, String responderProof, String targetCertificateSha256, UUID transferId) {

    @Override
    public String toString() {
        return "IssuedTicket[ticketId=" + ticketId + ", targetPeerId=" + targetPeerId + ", target=" + targetHost
                + ":" + targetPort + ", transferId=" + transferId + ", expiresAt=" + expiresAt
                + ", ticket=****, responderProof=****]";
    }
}
