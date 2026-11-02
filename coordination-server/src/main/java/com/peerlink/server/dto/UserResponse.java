package com.peerlink.server.dto;

import com.peerlink.server.entity.User;
import java.time.Instant;
import java.util.UUID;

/** Public view of an account. Deliberately has no password or password-hash field. */
public record UserResponse(UUID id, String username, String email, String displayName, boolean enabled,
                           Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getDisplayName(),
                user.isEnabled(), user.getCreatedAt());
    }
}
