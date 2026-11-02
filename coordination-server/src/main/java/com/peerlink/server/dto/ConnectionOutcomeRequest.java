package com.peerlink.server.dto;

import com.peerlink.server.entity.ConnectionOutcome;
import jakarta.validation.constraints.NotNull;

public record ConnectionOutcomeRequest(@NotNull ConnectionOutcome outcome) {
}
