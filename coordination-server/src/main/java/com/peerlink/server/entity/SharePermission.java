package com.peerlink.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Grants one user the right to request one shared file. Knowing a file's ID is never enough:
 * a transfer may only be authorized while an active permission exists (or for the owner).
 */
@Entity
@Table(name = "share_permissions")
public class SharePermission extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shared_file_id", nullable = false, updatable = false)
    private SharedFile sharedFile;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permitted_user_id", nullable = false, updatable = false)
    private User permittedUser;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "granted_by_id", nullable = false, updatable = false)
    private User grantedBy;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SharePermission() {
    }

    /**
     * @param expiresAt optional expiry; {@code null} means the permission lasts until revoked
     */
    public SharePermission(SharedFile sharedFile, User permittedUser, Instant expiresAt) {
        Objects.requireNonNull(sharedFile, "sharedFile");
        Objects.requireNonNull(permittedUser, "permittedUser");
        if (permittedUser.equals(sharedFile.getOwner())) {
            throw new IllegalArgumentException("A file owner does not need a permission for their own file");
        }
        this.sharedFile = sharedFile;
        this.permittedUser = permittedUser;
        // Only the owner can grant access to a file.
        this.grantedBy = sharedFile.getOwner();
        this.expiresAt = expiresAt;
    }

    public boolean isActive(Instant now) {
        return !revoked && (expiresAt == null || expiresAt.isAfter(now));
    }

    public void revoke(Instant now) {
        if (!revoked) {
            this.revoked = true;
            this.revokedAt = now;
        }
    }

    /** Re-grants a revoked or expired permission, reusing the single row per (file, user). */
    public void reinstate(Instant newExpiresAt) {
        this.revoked = false;
        this.revokedAt = null;
        this.expiresAt = newExpiresAt;
    }

    public SharedFile getSharedFile() {
        return sharedFile;
    }

    public User getPermittedUser() {
        return permittedUser;
    }

    public User getGrantedBy() {
        return grantedBy;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "SharePermission{id=" + getId() + ", revoked=" + revoked + ", expiresAt=" + expiresAt + '}';
    }
}
