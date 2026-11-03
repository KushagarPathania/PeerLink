package com.peerlink.server.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.net.InetAddress;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FileTransferTests {

    private static final String CHECKSUM = "a".repeat(64);

    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");
    private final User owner = new User("owner", "owner@example.com", "hash");
    private final User requester = new User("requester", "requester@example.com", "hash");
    private final Peer senderPeer = new Peer(owner, "sender", InetAddress.getLoopbackAddress(), 9090);
    private final Peer receiverPeer = new Peer(requester, "receiver", InetAddress.getLoopbackAddress(), 9091);
    private final SharedFile file = new SharedFile(senderPeer, "data.bin", 1000, CHECKSUM, 256);

    @Test
    void newTransferStartsRequestedWithTotalsFromFile() {
        FileTransfer transfer = new FileTransfer(file, receiverPeer, requester);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.REQUESTED);
        assertThat(transfer.getSenderPeer()).isSameAs(senderPeer);
        assertThat(transfer.getTotalBytes()).isEqualTo(1000);
        assertThat(transfer.getTotalChunks()).isEqualTo(4);
        assertThat(transfer.getChecksumVerified()).isNull();
        assertThat(transfer.getEvents()).singleElement().satisfies(event -> {
            assertThat(event.getFromStatus()).isNull();
            assertThat(event.getToStatus()).isEqualTo(TransferStatus.REQUESTED);
        });
    }

    @Test
    void completesOnlyAfterFullProgressAndRecordsEveryStep() {
        FileTransfer transfer = new FileTransfer(file, receiverPeer, requester);
        transfer.authorize();
        transfer.start(now);
        transfer.recordProgress(512, 2);
        transfer.recordProgress(1000, 4);
        transfer.completeWithVerifiedChecksum(now.plusSeconds(5));

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getChecksumVerified()).isTrue();
        assertThat(transfer.getStartedAt()).isEqualTo(now);
        assertThat(transfer.getCompletedAt()).isEqualTo(now.plusSeconds(5));
        assertThat(transfer.getEvents()).extracting(TransferEvent::getSequenceNumber).containsExactly(1, 2, 3, 4);
    }

    @Test
    void cannotCompleteBeforeAllBytesArrive() {
        FileTransfer transfer = inProgressTransfer();
        transfer.recordProgress(999, 4);

        assertThatIllegalStateException().isThrownBy(() -> transfer.completeWithVerifiedChecksum(now));
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.IN_PROGRESS);
    }

    @Test
    void cannotSkipAuthorization() {
        FileTransfer transfer = new FileTransfer(file, receiverPeer, requester);

        assertThatIllegalStateException().isThrownBy(() -> transfer.start(now));
    }

    @Test
    void terminalTransfersCannotChange() {
        FileTransfer transfer = new FileTransfer(file, receiverPeer, requester);
        transfer.cancel(now);

        assertThatIllegalStateException().isThrownBy(transfer::authorize);
        assertThatIllegalStateException().isThrownBy(() -> transfer.fail(TransferErrorCode.TIMEOUT, now));
    }

    @Test
    void progressMustStayWithinBoundsAndNeverDecrease() {
        FileTransfer transfer = inProgressTransfer();
        transfer.recordProgress(500, 2);

        assertThatIllegalArgumentException().isThrownBy(() -> transfer.recordProgress(1001, 4));
        assertThatIllegalArgumentException().isThrownBy(() -> transfer.recordProgress(1000, 5));
        assertThatIllegalArgumentException().isThrownBy(() -> transfer.recordProgress(400, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> transfer.recordProgress(600, 1));
    }

    @Test
    void progressIsRejectedOutsideInProgress() {
        FileTransfer transfer = new FileTransfer(file, receiverPeer, requester);

        assertThatIllegalStateException().isThrownBy(() -> transfer.recordProgress(10, 0));
    }

    @Test
    void checksumMismatchFailsTheTransfer() {
        FileTransfer transfer = inProgressTransfer();
        transfer.recordProgress(1000, 4);
        transfer.failChecksumVerification(now);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getChecksumVerified()).isFalse();
        assertThat(transfer.getErrorCode()).isEqualTo(TransferErrorCode.CHECKSUM_MISMATCH);
        assertThat(transfer.getEvents()).last().extracting(TransferEvent::getErrorCode)
                .isEqualTo(TransferErrorCode.CHECKSUM_MISMATCH);
    }

    @Test
    void receiverMustBelongToRequester() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FileTransfer(file, receiverPeer, owner));
    }

    @Test
    void senderAndReceiverMustDiffer() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FileTransfer(file, senderPeer, owner));
    }

    private FileTransfer inProgressTransfer() {
        FileTransfer transfer = new FileTransfer(file, receiverPeer, requester);
        transfer.authorize();
        transfer.start(now);
        return transfer;
    }

    @Test
    void peerCanRejectBeforeDataButNotDuringTransfer() {
        FileTransfer requested = new FileTransfer(file, receiverPeer, requester);
        requested.reject(TransferErrorCode.FILE_TOO_LARGE, now, receiverPeer);

        assertThat(requested.getStatus()).isEqualTo(TransferStatus.REJECTED);
        assertThat(requested.getErrorCode()).isEqualTo(TransferErrorCode.FILE_TOO_LARGE);
        assertThat(requested.getEvents()).last().satisfies(event -> {
            assertThat(event.getReportedByPeer()).isSameAs(receiverPeer);
            assertThat(event.getErrorCode()).isEqualTo(TransferErrorCode.FILE_TOO_LARGE);
        });
        assertThatIllegalStateException().isThrownBy(() -> requested.fail(TransferErrorCode.TIMEOUT, now));

        FileTransfer running = inProgressTransfer();
        assertThatIllegalStateException().isThrownBy(() -> running.reject(TransferErrorCode.PEER_BUSY, now, senderPeer));
    }

    @Test
    void recordsWhichPeerReportedEachChange() {
        FileTransfer transfer = new FileTransfer(file, receiverPeer, requester);
        transfer.authorize(senderPeer);
        transfer.start(now, receiverPeer);

        assertThat(transfer.getEvents()).extracting(TransferEvent::getReportedByPeer)
                .containsExactly(null, senderPeer, receiverPeer);
        assertThat(transfer.isActive()).isTrue();
    }
}
