package com.peerlink.server.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** A peer agent asks to connect directly to {@code targetPeerId}. */
public record ConnectionTicketRequest(@NotNull UUID targetPeerId) {
}
