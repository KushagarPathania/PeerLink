package com.peerlink.server.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peerlink.server.entity.SharePermission;
import com.peerlink.server.entity.SharedFile;
import com.peerlink.server.entity.User;
import com.peerlink.server.support.RepositoryTestSupport;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class SharePermissionRepositoryTests extends RepositoryTestSupport {

    @Autowired
    private SharePermissionRepository permissionRepository;

    private final Instant now = Instant.now();

    @Test
    void findsPermissionsForFileAndUser() {
        User owner = fixtures.user();
        User recipient = fixtures.user();
        SharedFile file = fixtures.file(fixtures.peer(owner));
        SharePermission permission = fixtures.permission(file, recipient, null);

        assertThat(permission.getGrantedBy()).isEqualTo(owner);
        assertThat(permissionRepository.findBySharedFileAndPermittedUser(file, recipient)).contains(permission);
        assertThat(permissionRepository.findBySharedFileAndPermittedUser(file, owner)).isEmpty();
        assertThat(permissionRepository.findBySharedFileOrderByCreatedAtAsc(file)).containsExactly(permission);
    }

    @Test
    void rejectsDuplicatePermissionForSameFileAndUser() {
        User recipient = fixtures.user();
        SharedFile file = fixtures.file(fixtures.peer(fixtures.user()));
        fixtures.permission(file, recipient, null);

        assertThatThrownBy(() -> permissionRepository.saveAndFlush(new SharePermission(file, recipient, null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_share_permissions_file_user");
    }

    @Test
    void activePermissionCheckHonoursRevocationAndExpiry() {
        User recipient = fixtures.user();
        User stranger = fixtures.user();
        SharedFile file = fixtures.file(fixtures.peer(fixtures.user()));
        SharePermission permission = fixtures.permission(file, recipient, now.plus(Duration.ofDays(1)));

        assertThat(permissionRepository.existsActivePermission(file.getId(), recipient.getId(), now)).isTrue();
        assertThat(permissionRepository.existsActivePermission(file.getId(), stranger.getId(), now)).isFalse();
        assertThat(permissionRepository.existsActivePermission(
                file.getId(), recipient.getId(), now.plus(Duration.ofDays(2)))).isFalse();

        permission.revoke(now);
        entityManager.flush();
        assertThat(permissionRepository.existsActivePermission(file.getId(), recipient.getId(), now)).isFalse();

        permission.reinstate(null);
        entityManager.flush();
        assertThat(permissionRepository.existsActivePermission(file.getId(), recipient.getId(), now)).isTrue();
    }

    @Test
    void databaseRequiresRevokedTimestampWhenRevoked() {
        SharePermission permission = fixtures.permission(fixtures.file(fixtures.peer(fixtures.user())), fixtures.user(), null);

        assertSqlViolates("ck_share_permissions_revocation",
                "update share_permissions set revoked = true where id = ?", permission.getId());
    }

    @Test
    void databaseRejectsExpiryBeforeCreation() {
        SharePermission permission = fixtures.permission(fixtures.file(fixtures.peer(fixtures.user())), fixtures.user(), null);

        assertSqlViolates("ck_share_permissions_expiry",
                "update share_permissions set expires_at = created_at - interval '1 hour' where id = ?", permission.getId());
    }

    @Test
    void databaseOnlyAllowsTheFileOwnerToGrantPermissions() {
        SharePermission permission = fixtures.permission(fixtures.file(fixtures.peer(fixtures.user())), fixtures.user(), null);
        User notTheOwner = fixtures.user();

        assertSqlViolates("fk_share_permissions_granted_by_owner",
                "update share_permissions set granted_by_id = ? where id = ?", notTheOwner.getId(), permission.getId());
    }

    @Test
    void permissionsAreRemovedTogetherWithTheirFile() {
        SharedFile file = fixtures.file(fixtures.peer(fixtures.user()));
        fixtures.permission(file, fixtures.user(), null);
        fixtures.permission(file, fixtures.user(), null);
        entityManager.clear();

        executeSql("delete from shared_files where id = ?", file.getId());

        assertThat(permissionRepository.count()).isZero();
    }
}
