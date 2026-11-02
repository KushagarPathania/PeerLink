package com.peerlink.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** The ticket a connecting peer presented, forwarded by the target peer's agent for verification. */
public record TicketRedemptionRequest(
        @NotBlank
        @Pattern(regexp = "^plt_[A-Za-z0-9_-]{43}$", message = "is not a connection ticket")
        String ticket) {

    @Override
    public String toString() {
        return "TicketRedemptionRequest[ticket=****]";
    }
}
