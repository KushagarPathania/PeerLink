package com.peerlink.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.peerlink.server.config.TransferProperties;
import com.peerlink.server.entity.FileTransfer;
import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.TransferErrorCode;
import com.peerlink.server.entity.TransferStatus;
import com.peerlink.server.repository.FileTransferRepository;
import com.peerlink.server.support.RepositoryTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class TransferSweeperTests extends RepositoryTestSupport {

    @Autowired
    private FileTransferRepository transferRepository;

    @Test
    void failsExpiredRequestsAndStalledTransfersOnly() {
        Peer sender = fixtures.peer(fixtures.user());
        Peer receiver = fixtures.peer(fixtures.user());
        FileTransfer waiting = fixtures.transfer(fixtures.file(sender), receiver);
        FileTransfer running = fixtures.transfer(fixtures.file(sender), receiver);
        running.authorize(sender);
        running.start(Instant.now(), receiver);
        entityManager.flush();

        TransferSweeper sweeper = new TransferSweeper(transferRepository,
                new TransferProperties(Duration.ofMinutes(30), Duration.ofMinutes(3)),
                mock(TransactionTemplate.class), Clock.systemUTC());

        // Nothing is old enough yet.
        sweeper.failStale(Instant.now());
        assertThat(waiting.getStatus()).isEqualTo(TransferStatus.REQUESTED);
        assertThat(running.getStatus()).isEqualTo(TransferStatus.IN_PROGRESS);

        // Four minutes later the running transfer stalled; the request is still within its TTL.
        sweeper.failStale(Instant.now().plus(Duration.ofMinutes(4)));
        assertThat(running.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(running.getErrorCode()).isEqualTo(TransferErrorCode.TIMEOUT);
        assertThat(waiting.getStatus()).isEqualTo(TransferStatus.REQUESTED);

        sweeper.failStale(Instant.now().plus(Duration.ofMinutes(31)));
        assertThat(waiting.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(waiting.getErrorCode()).isEqualTo(TransferErrorCode.REQUEST_EXPIRED);
    }
}
