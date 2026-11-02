package com.peerlink.server.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Asks for a shared file to be sent to one of the requester's own peers. */
public record TransferCreateRequest(
        @NotNull
        UUID sharedFileId,

        @NotNull
        UUID receiverPeerId) {
}
