package com.peerlink.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The ticket a connecting peer presented, forwarded by the target peer's agent for verification,
 * with the fingerprint of the TLS client certificate it was presented over. The server checks
 * that fingerprint before consuming the ticket, so a stolen ticket used with another key neither
 * works nor burns the legitimate peer's ticket.
 */
public record TicketRedemptionRequest(
        @NotBlank
        @Pattern(regexp = "^plt_[A-Za-z0-9_-]{43}$", message = "is not a connection ticket")
        String ticket,

        @NotBlank
        @Pattern(regexp = "^[0-9a-f]{64}$", message = "must be a lower-case hexadecimal SHA-256 fingerprint")
        String presentedCertificateSha256) {

    @Override
    public String toString() {
        return "TicketRedemptionRequest[ticket=****]";
    }
}
