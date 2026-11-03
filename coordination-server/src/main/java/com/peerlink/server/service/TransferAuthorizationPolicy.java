package com.peerlink.server.service;

import com.peerlink.server.entity.FileTransfer;
import com.peerlink.server.entity.SharedFile;
import com.peerlink.server.entity.User;
import com.peerlink.server.repository.SharePermissionRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The one place that decides whether a user may receive a shared file. Default deny: the file
 * must still be shared, both accounts enabled, and the user must be the owner or hold an active
 * (unrevoked, unexpired) permission. Knowing a file id or holding a connection ticket is never enough.
 * It is evaluated when a transfer is requested, when its ticket is issued and redeemed, and on
 * every progress report and sender status check, so revocation stops transfers in flight.
 */
@Component
public class TransferAuthorizationPolicy {

    private final SharePermissionRepository permissionRepository;

    public TransferAuthorizationPolicy(SharePermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    public boolean mayReceive(User user, SharedFile file, Instant now) {
        if (!file.isAvailable() || !user.isEnabled() || !file.getOwner().isEnabled()) {
            return false;
        }
        return file.getOwner().getId().equals(user.getId())
                || permissionRepository.existsActivePermission(file.getId(), user.getId(), now);
    }

    public boolean stillAuthorized(FileTransfer transfer, Instant now) {
        return transfer.isActive() && mayReceive(transfer.getRequestedBy(), transfer.getSharedFile(), now);
    }
}
