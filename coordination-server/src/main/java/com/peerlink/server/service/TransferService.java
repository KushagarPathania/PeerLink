package com.peerlink.server.service;

import com.peerlink.server.config.PeerPresenceProperties;
import com.peerlink.server.dto.FileTransferResponse;
import com.peerlink.server.dto.PageResponse;
import com.peerlink.server.dto.TransferCreateRequest;
import com.peerlink.server.entity.FileTransfer;
import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.SharedFile;
import com.peerlink.server.entity.TransferStatus;
import com.peerlink.server.entity.User;
import com.peerlink.server.exception.AccountNotFoundException;
import com.peerlink.server.exception.InvalidRequestException;
import com.peerlink.server.exception.PeerNotFoundException;
import com.peerlink.server.exception.SharedFileNotFoundException;
import com.peerlink.server.exception.TransferNotFoundException;
import com.peerlink.server.exception.TransferNotPossibleException;
import com.peerlink.server.exception.TransferStateConflictException;
import com.peerlink.server.repository.FileTransferRepository;
import com.peerlink.server.repository.PeerRepository;
import com.peerlink.server.repository.SharedFileRepository;
import com.peerlink.server.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transfers as seen by signed-in users. A user requests a file to be sent to one of their own
 * devices; the devices' agents then move the bytes directly. Users can see and cancel transfers
 * they requested or of files they own, and nothing else.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final FileTransferRepository transferRepository;
    private final SharedFileRepository fileRepository;
    private final PeerRepository peerRepository;
    private final UserRepository userRepository;
    private final TransferAuthorizationPolicy policy;
    private final PeerPresenceProperties presenceProperties;
    private final Clock clock;

    public TransferService(FileTransferRepository transferRepository, SharedFileRepository fileRepository,
                           PeerRepository peerRepository, UserRepository userRepository,
                           TransferAuthorizationPolicy policy, PeerPresenceProperties presenceProperties, Clock clock) {
        this.transferRepository = transferRepository;
        this.fileRepository = fileRepository;
        this.peerRepository = peerRepository;
        this.userRepository = userRepository;
        this.policy = policy;
        this.presenceProperties = presenceProperties;
        this.clock = clock;
    }

    @Transactional
    public FileTransferResponse request(UUID userId, TransferCreateRequest request) {
        Instant now = now();
        User user = userRepository.findById(userId).filter(User::isEnabled).orElseThrow(AccountNotFoundException::new);
        Peer receiver = peerRepository.findByIdAndUserId(request.receiverPeerId(), userId)
                .orElseThrow(PeerNotFoundException::new);
        // A file the user may not receive looks exactly like one that does not exist.
        SharedFile file = fileRepository.findById(request.sharedFileId())
                .filter(candidate -> policy.mayReceive(user, candidate, now))
                .orElseThrow(SharedFileNotFoundException::new);
        Peer sender = file.getSourcePeer();
        if (sender.getId().equals(receiver.getId())) {
            throw new InvalidRequestException("receiverPeerId", "This device already has the file.");
        }
        if (!sender.isOnline(now, presenceProperties.staleTimeout())) {
            throw new TransferNotPossibleException("The device sharing this file is offline. Try again when it is online.");
        }
        if (transferRepository.existsBySharedFileIdAndReceiverPeerIdAndStatusIn(file.getId(), receiver.getId(),
                TransferStatus.activeStatuses())) {
            throw new TransferNotPossibleException("This file is already being sent to that device.");
        }
        FileTransfer transfer = transferRepository.save(new FileTransfer(file, receiver, user));
        log.info("User {} requested transfer {} of file {} to peer {}", userId, transfer.getId(), file.getId(),
                receiver.getId());
        return FileTransferResponse.from(transfer);
    }

    @Transactional(readOnly = true)
    public PageResponse<FileTransferResponse> history(UUID userId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, PeerService.MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(transferRepository.findHistoryForUserId(userId, pageRequest), FileTransferResponse::from);
    }

    @Transactional(readOnly = true)
    public FileTransferResponse get(UUID userId, UUID transferId) {
        return FileTransferResponse.from(visible(userId, transferId));
    }

    /** The requester or the file's owner may cancel an unfinished transfer; agents notice within seconds. */
    @Transactional
    public FileTransferResponse cancel(UUID userId, UUID transferId) {
        FileTransfer transfer = visible(userId, transferId);
        if (!transfer.isActive()) {
            throw new TransferStateConflictException("The transfer has already finished.");
        }
        transfer.cancel(now());
        log.info("User {} cancelled transfer {}", userId, transferId);
        return FileTransferResponse.from(transfer);
    }

    private FileTransfer visible(UUID userId, UUID transferId) {
        return transferRepository.findVisibleToUser(transferId, userId).orElseThrow(TransferNotFoundException::new);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
