package com.peerlink.server.dto;

import com.peerlink.server.entity.User;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Grants another user access to a file; {@code expiresAt} is optional. */
public record SharePermissionRequest(
        @NotBlank
        @Size(max = User.USERNAME_MAX_LENGTH)
        String username,

        @Future
        Instant expiresAt) {
}
