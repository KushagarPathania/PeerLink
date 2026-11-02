package com.peerlink.server.service;

import com.peerlink.server.config.PeerPresenceProperties;
import com.peerlink.server.dto.HeartbeatResponse;
import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.PeerStatus;
import com.peerlink.server.exception.PeerNotFoundException;
import com.peerlink.server.repository.PeerRepository;
import com.peerlink.server.security.PeerAgentPrincipal;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Heartbeats and the online/offline rule. A peer is online exactly while
 * {@code lastHeartbeatAt >= now - staleTimeout}, using server time only. The same rule is used by
 * {@link Peer#isOnline}, the discovery query and the stale-peer sweep.
 */
@Service
public class PeerPresenceService {

    private static final Logger log = LoggerFactory.getLogger(PeerPresenceService.class);

    private final PeerRepository peerRepository;
    private final PeerPresenceProperties properties;
    private final Clock clock;

    public PeerPresenceService(PeerRepository peerRepository, PeerPresenceProperties properties, Clock clock) {
        this.peerRepository = peerRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Records a heartbeat from an authenticated agent. Repeating it is harmless: each call simply
     * moves {@code lastHeartbeatAt} to the current server time.
     *
     * @param observedAddress the remote address of the agent's connection, as seen by the server
     */
    @Transactional
    public HeartbeatResponse recordHeartbeat(UUID peerId, PeerAgentPrincipal agent, InetAddress observedAddress) {
        return recordHeartbeat(peerId, agent, observedAddress, null);
    }

    /**
     * @param listeningPort the port the agent reports listening on, or {@code null} to keep the
     *                      registered one; the address is always the observed one
     */
    @Transactional
    public HeartbeatResponse recordHeartbeat(UUID peerId, PeerAgentPrincipal agent, InetAddress observedAddress,
                                             Integer listeningPort) {
        if (!agent.peerId().equals(peerId)) {
            throw new AccessDeniedException("This credential is not valid for the requested peer");
        }
        Peer peer = peerRepository.findById(peerId).orElseThrow(PeerNotFoundException::new);
        Instant now = now();
        InetAddress address = withoutScopeId(observedAddress);
        if (listeningPort == null) {
            peer.recordHeartbeat(address, now);
        } else {
            peer.recordHeartbeat(address, listeningPort, now);
        }
        return new HeartbeatResponse(peer.getId(), PeerStatus.ONLINE, now, address.getHostAddress(),
                properties.heartbeatInterval().toSeconds(), properties.staleTimeout().toSeconds());
    }

    /** Marks peers OFFLINE whose last heartbeat is older than the stale timeout; only those rows change. */
    @Transactional
    public int markStalePeersOffline() {
        Instant now = now();
        int updated = peerRepository.markStalePeersOffline(onlineCutoff(now), now);
        if (updated > 0) {
            log.info("Marked {} stale peer(s) offline", updated);
        }
        return updated;
    }

    /** Oldest heartbeat time that still counts as online at {@code now}. */
    public Instant onlineCutoff(Instant now) {
        return now.minus(properties.staleTimeout());
    }

    public Instant now() {
        // PostgreSQL stores microseconds; truncating keeps in-memory and stored values identical.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** PostgreSQL's inet type cannot store IPv6 zone ids such as {@code %en0}. */
    private static InetAddress withoutScopeId(InetAddress address) {
        if (address instanceof Inet6Address ipv6 && (ipv6.getScopeId() != 0 || ipv6.getScopedInterface() != null)) {
            try {
                return InetAddress.getByAddress(ipv6.getAddress());
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Invalid IPv6 address length", e);
            }
        }
        return address;
    }
}
