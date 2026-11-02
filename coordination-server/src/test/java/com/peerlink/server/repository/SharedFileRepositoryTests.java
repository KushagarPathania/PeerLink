package com.peerlink.server.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.SharedFile;
import com.peerlink.server.entity.User;
import com.peerlink.server.support.RepositoryTestSupport;
import com.peerlink.server.support.TestFixtures;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class SharedFileRepositoryTests extends RepositoryTestSupport {

    @Autowired
    private SharedFileRepository sharedFileRepository;

    @Test
    void persistsMetadataDerivedFromSourcePeer() {
        Peer peer = fixtures.peer(fixtures.user());
        SharedFile file = entityManager.persistAndFlush(
                new SharedFile(peer, "report.pdf", 1000, TestFixtures.CHECKSUM.toUpperCase(), 256));
        entityManager.clear();

        SharedFile loaded = sharedFileRepository.findById(file.getId()).orElseThrow();
        assertThat(loaded.getOwner().getId()).isEqualTo(peer.getUser().getId());
        assertThat(loaded.getSourcePeer().getId()).isEqualTo(peer.getId());
        assertThat(loaded.getSha256Checksum()).isEqualTo(TestFixtures.CHECKSUM);
        assertThat(loaded.getTotalChunks()).isEqualTo(4);
        assertThat(loaded.isAvailable()).isTrue();
    }

    @Test
    void pagesThroughAvailableFilesOfOwner() {
        User owner = fixtures.user();
        Peer peer = fixtures.peer(owner);
        fixtures.file(peer, "a.txt");
        fixtures.file(peer, "b.txt");
        fixtures.file(peer, "c.txt");
        fixtures.file(peer, "hidden.txt").markUnavailable();
        fixtures.file(fixtures.peer(fixtures.user()), "someone-else.txt");
        entityManager.flush();

        Page<SharedFile> firstPage = sharedFileRepository.findByOwnerAndAvailableTrue(
                owner, PageRequest.of(0, 2, Sort.by("fileName")));

        assertThat(firstPage.getContent()).extracting(SharedFile::getFileName).containsExactly("a.txt", "b.txt");
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(sharedFileRepository.findByOwner(owner, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(4);
    }

    @Test
    void findsFilesByOwnerAndName() {
        User owner = fixtures.user();
        SharedFile notes = fixtures.file(fixtures.peer(owner), "notes.md");
        fixtures.file(fixtures.peer(owner), "notes.md");
        fixtures.file(fixtures.peer(fixtures.user()), "notes.md");

        assertThat(sharedFileRepository.findByOwnerAndFileName(owner, "notes.md")).hasSize(2).contains(notes);
        assertThat(sharedFileRepository.findByIdAndOwner(notes.getId(), owner)).contains(notes);
        assertThat(sharedFileRepository.findByIdAndOwner(notes.getId(), fixtures.user())).isEmpty();
    }

    @Test
    void listsOnlyFilesSharedWithUserThroughActivePermissions() {
        Instant now = Instant.now();
        User owner = fixtures.user();
        User recipient = fixtures.user();
        Peer peer = fixtures.peer(owner);
        SharedFile shared = fixtures.file(peer, "shared.txt");
        SharedFile revoked = fixtures.file(peer, "revoked.txt");
        SharedFile expiring = fixtures.file(peer, "expiring.txt");
        SharedFile unavailable = fixtures.file(peer, "unavailable.txt");
        fixtures.file(peer, "private.txt");

        fixtures.permission(shared, recipient, null);
        fixtures.permission(revoked, recipient, null).revoke(now);
        fixtures.permission(expiring, recipient, now.plus(Duration.ofHours(1)));
        fixtures.permission(unavailable, recipient, null);
        unavailable.markUnavailable();
        entityManager.flush();

        Page<SharedFile> sharedNow = sharedFileRepository.findSharedWithUser(recipient, now, PageRequest.of(0, 10));
        Page<SharedFile> sharedLater = sharedFileRepository.findSharedWithUser(
                recipient, now.plus(Duration.ofHours(2)), PageRequest.of(0, 10));

        assertThat(sharedNow.getContent()).containsExactlyInAnyOrder(shared, expiring);
        assertThat(sharedNow.getTotalElements()).isEqualTo(2);
        assertThat(sharedLater.getContent()).containsExactly(shared);
        assertThat(sharedFileRepository.findSharedWithUser(owner, now, PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void databaseRejectsMalformedChecksum() {
        SharedFile file = fixtures.file(fixtures.peer(fixtures.user()));

        assertSqlViolates("ck_shared_files_checksum",
                "update shared_files set sha256_checksum = 'not-a-checksum' where id = ?", file.getId());
    }

    @Test
    void databaseRejectsUpperCaseChecksum() {
        SharedFile file = fixtures.file(fixtures.peer(fixtures.user()));

        assertSqlViolates("ck_shared_files_checksum",
                "update shared_files set sha256_checksum = ? where id = ?", TestFixtures.CHECKSUM.toUpperCase(), file.getId());
    }

    @Test
    void databaseRejectsNegativeFileSize() {
        SharedFile file = fixtures.file(fixtures.peer(fixtures.user()));

        // total_chunks = 0 keeps the chunk-count check satisfied so only the size check fails.
        assertSqlViolates("ck_shared_files_size",
                "update shared_files set file_size_bytes = -1, total_chunks = 0 where id = ?", file.getId());
    }

    @Test
    void databaseRejectsInconsistentChunkCount() {
        SharedFile file = fixtures.file(fixtures.peer(fixtures.user()));

        assertSqlViolates("ck_shared_files_total_chunks",
                "update shared_files set total_chunks = 99 where id = ?", file.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../etc/passwd", "dir/file.txt", "C:\\Users\\file.txt", "..", ".", " ", "bad\nname"})
    void databaseRejectsPathsAndUnsafeFileNames(String fileName) {
        SharedFile file = fixtures.file(fixtures.peer(fixtures.user()));

        assertSqlViolates("ck_shared_files_file_name",
                "update shared_files set file_name = ? where id = ?", fileName, file.getId());
    }

    @Test
    void databaseRejectsSourcePeerOwnedBySomeoneElse() {
        SharedFile file = fixtures.file(fixtures.peer(fixtures.user()));
        User otherUser = fixtures.user();

        assertSqlViolates("fk_shared_files_source_peer_owner",
                "update shared_files set owner_id = ? where id = ?", otherUser.getId(), file.getId());
    }

    @Test
    void deletingPeerWithSharedFilesIsRestricted() {
        Peer peer = fixtures.peer(fixtures.user());
        fixtures.file(peer);

        assertSqlViolates("fk_shared_files_source_peer_owner", "delete from peers where id = ?", peer.getId());
    }
}
