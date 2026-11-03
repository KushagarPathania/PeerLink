package com.peerlink.server.dto;

import com.peerlink.server.entity.Peer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * Optional heartbeat body. An agent that runs a listener reports the port it actually listens on
 * and the fingerprint of the TLS certificate it presents there. The address is never taken from
 * the body.
 */
public record HeartbeatRequest(
        @Min(Peer.MIN_PORT) @Max(Peer.MAX_PORT) Integer listeningPort,
        @Pattern(regexp = "^[0-9a-f]{64}$", message = "must be a lower-case hexadecimal SHA-256 fingerprint")
        String tlsCertificateSha256) {
}
