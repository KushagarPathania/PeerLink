package com.peerlink.server.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EntityBehaviourTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");
    private final User owner = new User("Owner", " Owner@Example.com ", "hash");
    private final Peer peer = new Peer(owner, " laptop ", InetAddress.getLoopbackAddress(), 9090);

    @Test
    void userNormalizesUsernameAndEmail() {
        assertThat(owner.getUsername()).isEqualTo("owner");
        assertThat(owner.getEmail()).isEqualTo("owner@example.com");
        assertThat(owner.toString()).doesNotContain("hash");
    }

    @Test
    void peerIsOnlineOnlyWhileHeartbeatsAreRecent() {
        assertThat(peer.isOnline(now, TIMEOUT)).isFalse();

        peer.recordHeartbeat(InetAddress.getLoopbackAddress(), 9090, now);

        assertThat(peer.getStatus()).isEqualTo(PeerStatus.ONLINE);
        assertThat(peer.isOnline(now.plus(TIMEOUT), TIMEOUT)).isTrue();
        assertThat(peer.isOnline(now.plus(TIMEOUT).plusSeconds(1), TIMEOUT)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"0, 256, 0", "1, 256, 1", "256, 256, 1", "257, 256, 2", "1000, 256, 4"})
    void calculatesChunkCount(long size, int chunkSize, int expectedChunks) {
        assertThat(SharedFile.calculateTotalChunks(size, chunkSize)).isEqualTo(expectedChunks);
    }

    @Test
    void sharedFileTakesOwnerFromSourcePeerAndNormalizesChecksum() {
        SharedFile file = new SharedFile(peer, "a.txt", 10, "AB".repeat(32), 4);

        assertThat(file.getOwner()).isSameAs(owner);
        assertThat(file.getSha256Checksum()).isEqualTo("ab".repeat(32));
        assertThat(file.getTotalChunks()).isEqualTo(3);
    }

    @Test
    void sharedFileRejectsInvalidSizesAndChunking() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SharedFile(peer, "a", -1, "a".repeat(64), 4));
        assertThatIllegalArgumentException().isThrownBy(() -> new SharedFile(peer, "a", 1, "a".repeat(64), 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new SharedFile(peer, "a", Long.MAX_VALUE, "a".repeat(64), 1));
    }

    @Test
    void permissionCanExpireBeRevokedAndReinstated() {
        User recipient = new User("recipient", "r@example.com", "hash");
        SharedFile file = new SharedFile(peer, "a.txt", 10, "a".repeat(64), 4);
        SharePermission permission = new SharePermission(file, recipient, now.plusSeconds(60));

        assertThat(permission.getGrantedBy()).isSameAs(owner);
        assertThat(permission.isActive(now)).isTrue();
        assertThat(permission.isActive(now.plusSeconds(60))).isFalse();

        permission.revoke(now);
        assertThat(permission.isActive(now)).isFalse();
        assertThat(permission.getRevokedAt()).isEqualTo(now);

        permission.reinstate(null);
        assertThat(permission.isActive(now.plus(Duration.ofDays(365)))).isTrue();
        assertThat(permission.getRevokedAt()).isNull();
    }

    @Test
    void ownerCannotBeGrantedPermissionToOwnFile() {
        SharedFile file = new SharedFile(peer, "a.txt", 10, "a".repeat(64), 4);

        assertThatIllegalArgumentException().isThrownBy(() -> new SharePermission(file, owner, null));
    }
}
