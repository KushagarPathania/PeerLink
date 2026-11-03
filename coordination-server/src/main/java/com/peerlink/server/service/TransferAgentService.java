package com.peerlink.server.service;

import com.peerlink.server.config.PeerPresenceProperties;
import com.peerlink.server.dto.PendingTransferResponse;
import com.peerlink.server.dto.TransferCompletionRequest;
import com.peerlink.server.dto.TransferProgressRequest;
import com.peerlink.server.dto.TransferStatusResponse;
import com.peerlink.server.entity.FileTransfer;
import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.TransferErrorCode;
import com.peerlink.server.entity.TransferStatus;
import com.peerlink.server.exception.InvalidRequestException;
import com.peerlink.server.exception.TransferNotFoundException;
import com.peerlink.server.exception.TransferStateConflictException;
import com.peerlink.server.repository.FileTransferRepository;
import com.peerlink.server.security.PeerAgentPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transfer reports from peer agents. Each agent acts only for its own peer, and only the roles
 * that make sense may report each change: the receiver reports progress and completion (it is
 * the side that verifies the checksum); either side may report failure or rejection. Every change
 * is recorded with the reporting peer. The server never sees file bytes, so a completion report
 * is checked against the registered size and SHA-256 rather than trusted blindly.
 */
@Service
public class TransferAgentService {

    private static final Logger log = LoggerFactory.getLogger(TransferAgentService.class);

    private final FileTransferRepository transferRepository;
    private final TransferAuthorizationPolicy policy;
    private final PeerPresenceProperties presenceProperties;
    private final Clock clock;

    public TransferAgentService(FileTransferRepository transferRepository, TransferAuthorizationPolicy policy,
                                PeerPresenceProperties presenceProperties, Clock clock) {
        this.transferRepository = transferRepository;
        this.policy = policy;
        this.presenceProperties = presenceProperties;
        this.clock = clock;
    }

    /** The oldest requested transfer the calling (receiving) peer can fetch now, if any. */
    @Transactional(readOnly = true)
    public Optional<PendingTransferResponse> next(PeerAgentPrincipal agent, UUID peerId) {
        requireOwnPeer(agent, peerId);
        Instant now = now();
        return transferRepository.findPendingForReceiver(peerId, now.minus(presenceProperties.staleTimeout()), now,
                        PageRequest.of(0, 1)).stream()
                .findFirst()
                .map(PendingTransferResponse::from);
    }

    /** For either peer of the transfer, typically the sender re-checking authorization while streaming. */
    @Transactional(readOnly = true)
    public TransferStatusResponse status(PeerAgentPrincipal agent, UUID peerId, UUID transferId) {
        FileTransfer transfer = participating(agent, peerId, transferId);
        return statusOf(transfer, now());
    }

    /**
     * Receiver's progress. The first report moves an accepted transfer to IN_PROGRESS. If access
     * was revoked meanwhile, the transfer fails here and the returned status tells the agent to stop.
     */
    @Transactional
    public TransferStatusResponse progress(PeerAgentPrincipal agent, UUID peerId, UUID transferId,
                                           TransferProgressRequest report) {
        FileTransfer transfer = participating(agent, peerId, transferId);
        Peer reporter = requireReceiver(transfer, peerId);
        Instant now = now();
        if (!transfer.isActive()) {
            return statusOf(transfer, now);
        }
        if (!policy.stillAuthorized(transfer, now)) {
            transfer.fail(TransferErrorCode.PERMISSION_DENIED, now, reporter);
            log.warn("Transfer {} stopped: access is no longer authorized", transferId);
            return statusOf(transfer, now);
        }
        if (transfer.getStatus() == TransferStatus.REQUESTED) {
            throw new TransferStateConflictException("The sender has not accepted this transfer.");
        }
        if (transfer.getStatus() == TransferStatus.AUTHORIZED) {
            transfer.start(now, reporter);
        }
        try {
            transfer.recordProgress(report.bytesTransferred(), report.chunksCompleted());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("bytesTransferred", "Progress must not go backwards or exceed the file size.");
        }
        return statusOf(transfer, now);
    }

    /**
     * Receiver verified the size and SHA-256 and saved the file. Idempotent: the agent retries a
     * report whose answer it did not get, so repeating the same report for a transfer that is
     * already COMPLETED returns its status without recording anything again. Any other finished
     * state is a conflict. A report that does not match the registered metadata, or arrives after
     * access was revoked, fails the transfer; that failure must be committed even though the
     * request itself is answered with an error.
     */
    @Transactional(noRollbackFor = {InvalidRequestException.class, TransferStateConflictException.class})
    public TransferStatusResponse complete(PeerAgentPrincipal agent, UUID peerId, UUID transferId,
                                           TransferCompletionRequest report) {
        FileTransfer transfer = participating(agent, peerId, transferId);
        Peer reporter = requireReceiver(transfer, peerId);
        Instant now = now();
        boolean matches = report.bytesTransferred() == transfer.getTotalBytes()
                && report.sha256Checksum().equals(transfer.getSharedFile().getSha256Checksum());
        if (transfer.getStatus() == TransferStatus.COMPLETED && matches) {
            log.info("Transfer {}: repeated completion report from peer {} acknowledged", transferId, peerId);
            return statusOf(transfer, now);
        }
        if (transfer.getStatus() != TransferStatus.AUTHORIZED && transfer.getStatus() != TransferStatus.IN_PROGRESS) {
            throw new TransferStateConflictException("The transfer is " + transfer.getStatus() + " and cannot complete.");
        }
        if (transfer.getStatus() == TransferStatus.AUTHORIZED) {
            transfer.start(now, reporter);
        }
        if (!matches) {
            transfer.fail(TransferErrorCode.CHECKSUM_MISMATCH, now, reporter);
            log.warn("Transfer {} reported completion with a size or checksum that does not match the shared file",
                    transferId);
            throw new InvalidRequestException("sha256Checksum", "Size or checksum does not match the shared file.");
        }
        if (!policy.stillAuthorized(transfer, now)) {
            transfer.fail(TransferErrorCode.PERMISSION_DENIED, now, reporter);
            log.warn("Transfer {} reported complete after access was revoked; recorded as failed", transferId);
            throw new TransferStateConflictException("Access to the file is no longer authorized; the transfer failed.");
        }
        transfer.recordProgress(transfer.getTotalBytes(), transfer.getTotalChunks());
        transfer.completeWithVerifiedChecksum(now, reporter);
        log.info("Transfer {} completed ({} bytes, checksum verified by receiver peer {})", transferId,
                transfer.getTotalBytes(), peerId);
        return statusOf(transfer, now);
    }

    @Transactional
    public TransferStatusResponse fail(PeerAgentPrincipal agent, UUID peerId, UUID transferId, TransferErrorCode code) {
        FileTransfer transfer = participating(agent, peerId, transferId);
        requireActive(transfer);
        transfer.fail(code, now(), reporterOf(transfer, peerId));
        log.info("Transfer {} failed ({}), reported by peer {}", transferId, code, peerId);
        return statusOf(transfer, now());
    }

    /** A peer declines the transfer before data is accepted (only while REQUESTED or AUTHORIZED). */
    @Transactional
    public TransferStatusResponse reject(PeerAgentPrincipal agent, UUID peerId, UUID transferId, TransferErrorCode code) {
        FileTransfer transfer = participating(agent, peerId, transferId);
        if (!transfer.getStatus().canTransitionTo(TransferStatus.REJECTED)) {
            throw new TransferStateConflictException("The transfer is " + transfer.getStatus() + " and cannot be rejected.");
        }
        transfer.reject(code, now(), reporterOf(transfer, peerId));
        log.info("Transfer {} rejected ({}), reported by peer {}", transferId, code, peerId);
        return statusOf(transfer, now());
    }

    private TransferStatusResponse statusOf(FileTransfer transfer, Instant now) {
        return new TransferStatusResponse(transfer.getId(), transfer.getStatus(), policy.stillAuthorized(transfer, now),
                transfer.getTransferredBytes());
    }

    /** The transfer, if the calling agent's peer is its sender or receiver; otherwise "not found". */
    private FileTransfer participating(PeerAgentPrincipal agent, UUID peerId, UUID transferId) {
        requireOwnPeer(agent, peerId);
        FileTransfer transfer = transferRepository.findById(transferId).orElseThrow(TransferNotFoundException::new);
        if (!transfer.getSenderPeer().getId().equals(peerId) && !transfer.getReceiverPeer().getId().equals(peerId)) {
            throw new TransferNotFoundException();
        }
        return transfer;
    }

    private static Peer requireReceiver(FileTransfer transfer, UUID peerId) {
        if (!transfer.getReceiverPeer().getId().equals(peerId)) {
            throw new AccessDeniedException("Only the receiving peer reports progress and completion");
        }
        return transfer.getReceiverPeer();
    }

    private static Peer reporterOf(FileTransfer transfer, UUID peerId) {
        return transfer.getSenderPeer().getId().equals(peerId) ? transfer.getSenderPeer() : transfer.getReceiverPeer();
    }

    private static void requireActive(FileTransfer transfer) {
        if (!transfer.isActive()) {
            throw new TransferStateConflictException("The transfer has already finished (" + transfer.getStatus() + ").");
        }
    }

    private static void requireOwnPeer(PeerAgentPrincipal agent, UUID peerId) {
        if (!agent.peerId().equals(peerId)) {
            throw new AccessDeniedException("This credential is not valid for the requested peer");
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
