package com.peerlink.server.service;

import com.peerlink.server.config.PeerPresenceProperties;
import com.peerlink.server.dto.PageResponse;
import com.peerlink.server.dto.SharePermissionRequest;
import com.peerlink.server.dto.SharePermissionResponse;
import com.peerlink.server.dto.SharedFileRegistrationRequest;
import com.peerlink.server.dto.SharedFileResponse;
import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.SharePermission;
import com.peerlink.server.entity.SharedFile;
import com.peerlink.server.entity.User;
import com.peerlink.server.exception.InvalidRequestException;
import com.peerlink.server.exception.PeerNotFoundException;
import com.peerlink.server.exception.SharedFileNotFoundException;
import com.peerlink.server.repository.PeerRepository;
import com.peerlink.server.repository.SharePermissionRepository;
import com.peerlink.server.repository.SharedFileRepository;
import com.peerlink.server.repository.UserRepository;
import com.peerlink.server.security.PeerAgentPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared-file metadata and per-user access. Files are registered by the agent of the device that
 * holds them (the browser cannot read local files); owners then grant access to specific users.
 */
@Service
public class SharedFileService {

    private static final Logger log = LoggerFactory.getLogger(SharedFileService.class);

    private final SharedFileRepository fileRepository;
    private final SharePermissionRepository permissionRepository;
    private final PeerRepository peerRepository;
    private final UserRepository userRepository;
    private final PeerPresenceProperties presenceProperties;
    private final Clock clock;

    public SharedFileService(SharedFileRepository fileRepository, SharePermissionRepository permissionRepository,
                             PeerRepository peerRepository, UserRepository userRepository,
                             PeerPresenceProperties presenceProperties, Clock clock) {
        this.fileRepository = fileRepository;
        this.permissionRepository = permissionRepository;
        this.peerRepository = peerRepository;
        this.userRepository = userRepository;
        this.presenceProperties = presenceProperties;
        this.clock = clock;
    }

    /** Registers a file held by the calling agent's peer; registering the same file again returns the existing record. */
    @Transactional
    public SharedFileResponse registerFromAgent(PeerAgentPrincipal agent, UUID peerId, SharedFileRegistrationRequest request) {
        if (!agent.peerId().equals(peerId)) {
            throw new AccessDeniedException("This credential is not valid for the requested peer");
        }
        int chunkSize = request.chunkSizeBytes();
        if (chunkSize < SharedFile.MIN_TRANSFER_CHUNK_SIZE_BYTES || chunkSize > SharedFile.MAX_TRANSFER_CHUNK_SIZE_BYTES) {
            throw new InvalidRequestException("chunkSizeBytes", "must be between 4096 and 65536 bytes");
        }
        Peer peer = peerRepository.findById(peerId).orElseThrow(PeerNotFoundException::new);
        String checksum = request.sha256Checksum().toLowerCase(java.util.Locale.ROOT);
        SharedFile file = fileRepository
                .findFirstBySourcePeerIdAndFileNameAndSha256ChecksumAndFileSizeBytesAndAvailableTrue(
                        peerId, request.fileName(), checksum, request.fileSizeBytes())
                .orElseGet(() -> {
                    SharedFile created = new SharedFile(peer, request.fileName(), request.fileSizeBytes(), checksum,
                            request.chunkSizeBytes());
                    created.setContentType(request.contentType());
                    SharedFile saved = fileRepository.save(created);
                    log.info("Peer {} registered shared file {} ({} bytes)", peerId, saved.getId(),
                            request.fileSizeBytes());
                    return saved;
                });
        return SharedFileResponse.from(file, true);
    }

    @Transactional(readOnly = true)
    public PageResponse<SharedFileResponse> listOwnFiles(UUID ownerId, int page, int size) {
        Instant now = now();
        return PageResponse.from(fileRepository.findByOwnerIdAndAvailableTrue(ownerId, pageRequest(page, size)),
                file -> SharedFileResponse.from(file, isOnline(file, now)));
    }

    /** Available files other users shared with {@code userId} through an active permission. */
    @Transactional(readOnly = true)
    public PageResponse<SharedFileResponse> listSharedWithUser(UUID userId, int page, int size) {
        Instant now = now();
        User user = userRepository.getReferenceById(userId);
        return PageResponse.from(fileRepository.findSharedWithUser(user, now, pageRequest(page, size)),
                file -> SharedFileResponse.from(file, isOnline(file, now)));
    }

    @Transactional
    public SharePermissionResponse grant(UUID ownerId, UUID fileId, SharePermissionRequest request) {
        SharedFile file = ownFile(ownerId, fileId);
        User recipient = userRepository.findByUsername(User.normalize(request.username()))
                .filter(User::isEnabled)
                .orElseThrow(() -> new InvalidRequestException("username", "No active user with that username."));
        if (recipient.getId().equals(ownerId)) {
            throw new InvalidRequestException("username", "You already have access to your own files.");
        }
        SharePermission permission = permissionRepository.findBySharedFileAndPermittedUser(file, recipient)
                .map(existing -> {
                    existing.reinstate(request.expiresAt());
                    return existing;
                })
                .orElseGet(() -> permissionRepository.save(new SharePermission(file, recipient, request.expiresAt())));
        log.info("User {} shared file {} with user {}", ownerId, fileId, recipient.getId());
        return SharePermissionResponse.from(permission, now());
    }

    @Transactional(readOnly = true)
    public List<SharePermissionResponse> listPermissions(UUID ownerId, UUID fileId) {
        Instant now = now();
        return permissionRepository.findBySharedFileOrderByCreatedAtAsc(ownFile(ownerId, fileId)).stream()
                .map(permission -> SharePermissionResponse.from(permission, now))
                .toList();
    }

    /** Revoking also stops running transfers: senders re-check authorization while they stream. */
    @Transactional
    public void revoke(UUID ownerId, UUID fileId, UUID permissionId) {
        ownFile(ownerId, fileId);
        SharePermission permission = permissionRepository.findByIdAndSharedFileId(permissionId, fileId)
                .orElseThrow(SharedFileNotFoundException::new);
        permission.revoke(now());
        log.info("User {} revoked permission {} on file {}", ownerId, permissionId, fileId);
    }

    /** Stops sharing a file; it disappears from listings and no new or running transfer may use it. */
    @Transactional
    public void stopSharing(UUID ownerId, UUID fileId) {
        ownFile(ownerId, fileId).markUnavailable();
        log.info("User {} stopped sharing file {}", ownerId, fileId);
    }

    private SharedFile ownFile(UUID ownerId, UUID fileId) {
        return fileRepository.findByIdAndOwnerId(fileId, ownerId)
                .filter(SharedFile::isAvailable)
                .orElseThrow(SharedFileNotFoundException::new);
    }

    private boolean isOnline(SharedFile file, Instant now) {
        return file.getSourcePeer().isOnline(now, presenceProperties.staleTimeout());
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, PeerService.MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
