package com.peerlink.server.scheduler;

import com.peerlink.server.config.TransferProperties;
import com.peerlink.server.entity.FileTransfer;
import com.peerlink.server.entity.TransferErrorCode;
import com.peerlink.server.entity.TransferStatus;
import com.peerlink.server.repository.FileTransferRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fails transfers that can no longer finish: requests nobody picked up, and accepted or running
 * transfers whose agents stopped reporting (crashed, disconnected or shut down). Interrupted
 * transfers are never resumed; they end as FAILED and can be requested again.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "peerlink.transfers", name = "sweep-enabled", havingValue = "true", matchIfMissing = true)
public class TransferSweeper implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(TransferSweeper.class);
    private static final Duration INTERVAL = Duration.ofSeconds(30);
    private static final int BATCH = 100;

    private final FileTransferRepository transferRepository;
    private final TransferProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public TransferSweeper(FileTransferRepository transferRepository, TransferProperties properties,
                           TransactionTemplate transactions, Clock clock) {
        this.transferRepository = transferRepository;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::sweep, INTERVAL);
    }

    void sweep() {
        try {
            Integer failed = transactions.execute(status -> failStale(clock.instant()));
            if (failed != null && failed > 0) {
                log.info("Failed {} stale transfer(s)", failed);
            }
        } catch (RuntimeException e) {
            log.warn("Transfer sweep failed: {}", e.getMessage());
        }
    }

    /** Package-visible for tests; runs inside a transaction. */
    int failStale(Instant now) {
        List<FileTransfer> expiredRequests = transferRepository.findByStatusInAndUpdatedAtBefore(
                EnumSet.of(TransferStatus.REQUESTED), now.minus(properties.requestTtl()), PageRequest.of(0, BATCH));
        expiredRequests.forEach(transfer -> transfer.fail(TransferErrorCode.REQUEST_EXPIRED, now));
        List<FileTransfer> stalled = transferRepository.findByStatusInAndUpdatedAtBefore(
                EnumSet.of(TransferStatus.AUTHORIZED, TransferStatus.IN_PROGRESS), now.minus(properties.stallTimeout()),
                PageRequest.of(0, BATCH));
        stalled.forEach(transfer -> transfer.fail(TransferErrorCode.TIMEOUT, now));
        return expiredRequests.size() + stalled.size();
    }
}
