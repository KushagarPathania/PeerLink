package com.peerlink.server.dto;

import com.peerlink.server.entity.SharePermission;
import java.time.Instant;
import java.util.UUID;

public record SharePermissionResponse(UUID id, UUID sharedFileId, String permittedUsername, String grantedByUsername,
                                      Instant createdAt, Instant expiresAt, boolean revoked, boolean active) {

    public static SharePermissionResponse from(SharePermission permission, Instant now) {
        return new SharePermissionResponse(permission.getId(), permission.getSharedFile().getId(),
                permission.getPermittedUser().getUsername(), permission.getGrantedBy().getUsername(),
                permission.getCreatedAt(), permission.getExpiresAt(), permission.isRevoked(),
                permission.isActive(now));
    }
}
