package com.peerlink.server.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.peerlink.server.entity.FileTransfer;
import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.SharedFile;
import com.peerlink.server.entity.TransferErrorCode;
import com.peerlink.server.entity.TransferEvent;
import com.peerlink.server.entity.TransferStatus;
import com.peerlink.server.entity.User;
import com.peerlink.server.support.RepositoryTestSupport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

class FileTransferRepositoryTests extends RepositoryTestSupport {

    @Autowired
    private FileTransferRepository transferRepository;

    @Autowired
    private TransferEventRepository eventRepository;

    private User owner;
    private User requester;
    private Peer senderPeer;
    private Peer receiverPeer;
    private SharedFile file;

    @BeforeEach
    void createParticipants() {
        owner = fixtures.user();
        requester = fixtures.user();
        senderPeer = fixtures.peer(owner);
        receiverPeer = fixtures.peer(requester);
        file = fixtures.file(senderPeer);
    }

    @Test
    void persistsFullLifecycleWithAuditTrail() {
        FileTransfer transfer = fixtures.transfer(file, receiverPeer);
        Instant now = Instant.now();
        transfer.authorize();
        transfer.start(now);
        transfer.recordProgress(file.getFileSizeBytes(), file.getTotalChunks());
        transfer.completeWithVerifiedChecksum(now.plusSeconds(1));
        entityManager.flush();
        entityManager.clear();

        FileTransfer loaded = transferRepository.findById(transfer.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(loaded.getChecksumVerified()).isTrue();
        assertThat(loaded.getVersion()).isPositive();

        List<TransferEvent> events = eventRepository.findByTransferOrderBySequenceNumberAsc(loaded);
        assertThat(events).extracting(TransferEvent::getSequenceNumber).containsExactly(1, 2, 3, 4);
        assertThat(events).extracting(TransferEvent::getToStatus).containsExactly(
                TransferStatus.REQUESTED, TransferStatus.AUTHORIZED, TransferStatus.IN_PROGRESS, TransferStatus.COMPLETED);
        assertThat(events.get(0).getFromStatus()).isNull();
    }

    @Test
    void findsTransfersByPeerRequesterOwnerAndStatus() {
        FileTransfer incoming = fixtures.transfer(file, receiverPeer);
        Peer requesterSecondPeer = fixtures.peer(requester);
        FileTransfer outgoing = fixtures.transfer(fixtures.file(receiverPeer), requesterSecondPeer);
        outgoing.cancel(Instant.now());
        entityManager.flush();
        PageRequest page = PageRequest.of(0, 10);

        assertThat(transferRepository.findInvolvingPeer(receiverPeer, page)).containsExactlyInAnyOrder(incoming, outgoing);
        assertThat(transferRepository.findInvolvingPeer(senderPeer, page)).containsExactly(incoming);
        assertThat(transferRepository.findByRequestedBy(requester, page)).containsExactlyInAnyOrder(incoming, outgoing);
        assertThat(transferRepository.findByRequestedBy(owner, page)).isEmpty();
        assertThat(transferRepository.findHistoryForUser(owner, page)).containsExactly(incoming);
        assertThat(transferRepository.findByStatus(TransferStatus.CANCELLED, page)).containsExactly(outgoing);
        assertThat(transferRepository.findByStatusInAndUpdatedAtBefore(
                TransferStatus.activeStatuses(), Instant.now().plusSeconds(60))).containsExactly(incoming);
    }

    @Test
    void databaseRejectsTransferredBytesAboveTotal() {
        FileTransfer transfer = fixtures.transfer(file, receiverPeer);

        assertSqlViolates("ck_file_transfers_bytes",
                "update file_transfers set transferred_bytes = total_bytes + 1 where id = ?", transfer.getId());
    }

    @Test
    void databaseRejectsCompletedChunksAboveTotal() {
        FileTransfer transfer = fixtures.transfer(file, receiverPeer);

        assertSqlViolates("ck_file_transfers_chunks",
                "update file_transfers set completed_chunks = total_chunks + 1 where id = ?", transfer.getId());
    }

    @Test
    void databaseRejectsCompletionWithoutVerifiedChecksum() {
        FileTransfer transfer = fixtures.transfer(file, receiverPeer);

        assertSqlViolates("ck_file_transfers_completed_verified", """
                update file_transfers
                set status = 'COMPLETED', transferred_bytes = total_bytes, completed_chunks = total_chunks,
                    started_at = now(), completed_at = now(), checksum_verified = null
                where id = ?""", transfer.getId());
    }

    @Test
    void databaseRejectsFailureWithoutErrorCode() {
        FileTransfer transfer = fixtures.transfer(file, receiverPeer);

        assertSqlViolates("ck_file_transfers_failed_has_error",
                "update file_transfers set status = 'FAILED' where id = ?", transfer.getId());
    }

    @Test
    void databaseAcceptsEveryTransferErrorCode() {
        FileTransfer transfer = fixtures.transfer(file, receiverPeer);

        for (TransferErrorCode code : TransferErrorCode.values()) {
            assertThat(executeSql("update file_transfers set status = 'FAILED', error_code = ? where id = ?",
                    code.name(), transfer.getId())).isEqualTo(1);
        }
    }

    @Test
    void databaseRequiresReceiverToBelongToRequester() {
        FileTransfer transfer = fixtures.transfer(file, receiverPeer);

        assertSqlViolates("fk_file_transfers_receiver_requester",
                "update file_transfers set requested_by_id = ? where id = ?", owner.getId(), transfer.getId());
    }

    @Test
    void databaseRequiresSenderToBeTheFilesSourcePeer() {
        FileTransfer transfer = fixtures.transfer(file, receiverPeer);
        Peer otherOwnerPeer = fixtures.peer(owner);

        assertSqlViolates("fk_file_transfers_file_sender",
                "update file_transfers set sender_peer_id = ? where id = ?", otherOwnerPeer.getId(), transfer.getId());
    }

    @Test
    void deletingFileWithTransferHistoryIsRestricted() {
        fixtures.transfer(file, receiverPeer);

        assertSqlViolates("fk_file_transfers_file_sender", "delete from shared_files where id = ?", file.getId());
    }
}
