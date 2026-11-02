package com.peerlink.server.service;

import com.peerlink.server.config.PeerPresenceProperties;
import com.peerlink.server.dto.AgentCredentialResponse;
import com.peerlink.server.dto.DiscoveredPeerResponse;
import com.peerlink.server.dto.PageResponse;
import com.peerlink.server.dto.PeerRegistrationRequest;
import com.peerlink.server.dto.PeerRegistrationResponse;
import com.peerlink.server.dto.PeerResponse;
import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.User;
import com.peerlink.server.exception.AccountNotFoundException;
import com.peerlink.server.exception.DataIntegrityViolations;
import com.peerlink.server.exception.DeviceNameTakenException;
import com.peerlink.server.exception.PeerNotFoundException;
import com.peerlink.server.repository.PeerRepository;
import com.peerlink.server.repository.UserRepository;
import com.peerlink.server.security.PeerCredentialService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Device management for the signed-in user. Every method takes the caller's user id from the
 * security context; ownership is enforced by querying with that id, so another user's peer is
 * indistinguishable from a peer that does not exist.
 */
@Service
public class PeerService {

    public static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(PeerService.class);

    private final PeerRepository peerRepository;
    private final UserRepository userRepository;
    private final PeerCredentialService credentialService;
    private final PeerPresenceService presenceService;
    private final PeerPresenceProperties properties;

    public PeerService(PeerRepository peerRepository, UserRepository userRepository,
                       PeerCredentialService credentialService, PeerPresenceService presenceService,
                       PeerPresenceProperties properties) {
        this.peerRepository = peerRepository;
        this.userRepository = userRepository;
        this.credentialService = credentialService;
        this.presenceService = presenceService;
        this.properties = properties;
    }

    /** Registers a device for {@code ownerId} and issues its one-time agent credential. */
    @Transactional
    public PeerRegistrationResponse register(UUID ownerId, PeerRegistrationRequest request) {
        User owner = userRepository.findById(ownerId).filter(User::isEnabled).orElseThrow(AccountNotFoundException::new);
        String deviceName = request.deviceName().trim();
        if (peerRepository.existsByUserIdAndDeviceName(ownerId, deviceName)) {
            throw new DeviceNameTakenException();
        }
        Peer peer = new Peer(owner, deviceName, request.listeningPort());
        try {
            peerRepository.saveAndFlush(peer);
        } catch (DataIntegrityViolationException e) {
            if ("uk_peers_user_device_name".equals(DataIntegrityViolations.constraintName(e))) {
                throw new DeviceNameTakenException();
            }
            throw e;
        }
        AgentCredentialResponse credential = credentialService.issue(peer);
        log.info("Registered peer {} for user '{}'", peer.getId(), owner.getUsername());
        return new PeerRegistrationResponse(toResponse(peer), credential);
    }

    @Transactional(readOnly = true)
    public List<PeerResponse> listOwnPeers(UUID ownerId) {
        return peerRepository.findByUserIdOrderByDeviceNameAsc(ownerId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PeerResponse getOwnPeer(UUID ownerId, UUID peerId) {
        return toResponse(findOwnPeer(ownerId, peerId));
    }

    /** Replaces the peer's agent credential; the old one stops working immediately. */
    @Transactional
    public AgentCredentialResponse rotateCredential(UUID ownerId, UUID peerId) {
        Peer peer = findOwnPeer(ownerId, peerId);
        AgentCredentialResponse credential = credentialService.issue(peer);
        log.info("Rotated agent credential of peer {}", peer.getId());
        return credential;
    }

    @Transactional
    public void revokeCredential(UUID ownerId, UUID peerId) {
        Peer peer = findOwnPeer(ownerId, peerId);
        peer.revokeCredential();
        log.info("Revoked agent credential of peer {}", peer.getId());
    }

    /**
     * Online peers of <em>other</em> enabled users, judged by heartbeat age rather than the stored
     * status alone. Being listed does not guarantee a direct connection will succeed.
     */
    @Transactional(readOnly = true)
    public PageResponse<DiscoveredPeerResponse> discoverOnlinePeers(UUID userId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by("deviceName", "id"));
        Instant cutoff = presenceService.onlineCutoff(presenceService.now());
        return PageResponse.from(peerRepository.findOnlinePeersExcludingUser(cutoff, userId, pageRequest),
                DiscoveredPeerResponse::from);
    }

    private Peer findOwnPeer(UUID ownerId, UUID peerId) {
        return peerRepository.findByIdAndUserId(peerId, ownerId).orElseThrow(PeerNotFoundException::new);
    }

    private PeerResponse toResponse(Peer peer) {
        return PeerResponse.from(peer, presenceService.now(), properties.staleTimeout());
    }
}
