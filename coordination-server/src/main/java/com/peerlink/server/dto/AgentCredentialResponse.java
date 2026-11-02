package com.peerlink.server.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A freshly issued agent credential. This is the only time the plaintext credential leaves the
 * server; afterwards only its hash exists. {@link #toString()} masks it so it cannot reach logs.
 */
public record AgentCredentialResponse(UUID peerId, String credential, Instant issuedAt) {

    @Override
    public String toString() {
        return "AgentCredentialResponse[peerId=" + peerId + ", credential=****, issuedAt=" + issuedAt + "]";
    }
}
