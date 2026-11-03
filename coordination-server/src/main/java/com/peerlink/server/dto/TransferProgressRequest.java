package com.peerlink.server.dto;

import jakarta.validation.constraints.PositiveOrZero;

/** Receiver's periodic progress report (throttled by the agent; never one per chunk). */
public record TransferProgressRequest(@PositiveOrZero long bytesTransferred, @PositiveOrZero int chunksCompleted) {
}
