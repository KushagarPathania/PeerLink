package com.peerlink.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * A peer agent asks to connect directly to {@code targetPeerId}, optionally to fetch one specific
 * transfer. {@code initiatorCertificateSha256} is the fingerprint of the TLS certificate the agent
 * will present; the target only accepts a connection that presents exactly that certificate.
 */
public record ConnectionTicketRequest(
        @NotNull UUID targetPeerId,
        UUID transferId,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$", message = "must be a lower-case hexadecimal SHA-256 fingerprint")
        String initiatorCertificateSha256) {
}
