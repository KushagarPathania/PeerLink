package com.peerlink.server.service;

import com.peerlink.server.config.ConnectionProperties;
import com.peerlink.server.config.PeerPresenceProperties;
import com.peerlink.server.dto.ConnectionTicketResponse;
import com.peerlink.server.dto.TicketRedemptionResponse;
import com.peerlink.server.entity.ConnectionOutcome;
import com.peerlink.server.entity.ConnectionTicket;
import com.peerlink.server.entity.ConnectionTicketStatus;
import com.peerlink.server.entity.Peer;
import com.peerlink.server.exception.ConnectionTicketNotFoundException;
import com.peerlink.server.exception.InvalidConnectionTicketException;
import com.peerlink.server.exception.InvalidRequestException;
import com.peerlink.server.exception.OutcomeAlreadyReportedException;
import com.peerlink.server.exception.PeerNotFoundException;
import com.peerlink.server.repository.ConnectionTicketRepository;
import com.peerlink.server.repository.PeerRepository;
import com.peerlink.server.security.PeerAgentPrincipal;
import com.peerlink.server.security.SecretTokens;
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
 * Brokers direct peer-to-peer connections without carrying any of their traffic. All methods are
 * called by peer agents authenticated with their own device credential, and act only for that
 * agent's peer. Ticket values and proofs are never logged.
 */
@Service
public class ConnectionTicketService {

    public static final String TICKET_PREFIX = "plt_";

    private static final Logger log = LoggerFactory.getLogger(ConnectionTicketService.class);

    private final ConnectionTicketRepository ticketRepository;
    private final PeerRepository peerRepository;
    private final ConnectionProperties connectionProperties;
    private final PeerPresenceProperties presenceProperties;
    private final Clock clock;

    public ConnectionTicketService(ConnectionTicketRepository ticketRepository, PeerRepository peerRepository,
                                   ConnectionProperties connectionProperties,
                                   PeerPresenceProperties presenceProperties, Clock clock) {
        this.ticketRepository = ticketRepository;
        this.peerRepository = peerRepository;
        this.connectionProperties = connectionProperties;
        this.presenceProperties = presenceProperties;
        this.clock = clock;
    }

    /**
     * Issues a ticket for {@code sourcePeerId} (the calling agent's own peer) to connect to
     * {@code targetPeerId}. Phase 5 policy: any online peer of an enabled account may be contacted,
     * which is exactly the set of peers discovery shows, plus the caller's own other devices.
     * A target that does not exist, is offline or belongs to a disabled account looks the same (404).
     */
    @Transactional
    public ConnectionTicketResponse issue(PeerAgentPrincipal agent, UUID sourcePeerId, UUID targetPeerId) {
        requireOwnPeer(agent, sourcePeerId);
        if (sourcePeerId.equals(targetPeerId)) {
            throw new InvalidRequestException("targetPeerId", "A peer cannot connect to itself.");
        }
        Instant now = now();
        Peer source = peerRepository.findById(sourcePeerId).orElseThrow(PeerNotFoundException::new);
        Peer target = peerRepository.findById(targetPeerId)
                .filter(peer -> peer.isOnline(now, presenceProperties.staleTimeout()))
                .filter(peer -> peer.getUser().isEnabled() && peer.getHostAddress() != null)
                .orElseThrow(PeerNotFoundException::new);

        String ticket = TICKET_PREFIX + SecretTokens.randomValue();
        String responderProof = SecretTokens.randomValue();
        ConnectionTicket record = ticketRepository.save(new ConnectionTicket(source, target,
                SecretTokens.sha256Hex(ticket), responderProof, now, now.plus(connectionProperties.ticketTtl())));
        log.info("Issued connection ticket {} from peer {} to peer {} (expires {})",
                record.getId(), sourcePeerId, targetPeerId, record.getExpiresAt());
        return new ConnectionTicketResponse(record.getId(), ticket, sourcePeerId, targetPeerId,
                record.getTargetHost().getHostAddress(), record.getTargetPort(), record.getExpiresAt(), responderProof);
    }

    /**
     * Redeems a ticket presented to {@code targetPeerId} (the calling agent's own peer). Succeeds at
     * most once per ticket. Any failure leaves the caller with the same generic error; a ticket
     * presented to the wrong peer, or whose source credential has since changed, is also invalidated.
     */
    @Transactional(noRollbackFor = InvalidConnectionTicketException.class)
    public TicketRedemptionResponse redeem(PeerAgentPrincipal agent, UUID targetPeerId, String ticket) {
        requireOwnPeer(agent, targetPeerId);
        Instant now = now();
        ConnectionTicket record = ticketRepository.findByTicketHash(SecretTokens.sha256Hex(ticket))
                .orElseThrow(() -> rejected(targetPeerId, null, "unknown ticket"));

        if (!record.getTargetPeer().getId().equals(targetPeerId)) {
            record.invalidate();
            throw rejected(targetPeerId, record, "presented to the wrong peer; ticket invalidated");
        }
        if (record.getStatus() != ConnectionTicketStatus.ISSUED) {
            throw rejected(targetPeerId, record, "already " + record.getStatus().name().toLowerCase());
        }
        if (record.isExpired(now)) {
            record.invalidate();
            throw rejected(targetPeerId, record, "expired");
        }
        Peer source = record.getSourcePeer();
        boolean sourceStillAuthorized = source.getUser().isEnabled() && source.hasActiveCredential()
                && !record.getIssuedAt().isBefore(source.getCredentialIssuedAt());
        if (!sourceStillAuthorized) {
            record.invalidate();
            throw rejected(targetPeerId, record, "source credential revoked or rotated since issue");
        }

        String proof = record.redeem(now);
        log.info("Connection ticket {} redeemed by peer {} for source peer {}", record.getId(), targetPeerId,
                source.getId());
        return new TicketRedemptionResponse(record.getId(), source.getId(), source.getDeviceName(),
                source.getUser().getUsername(), proof);
    }

    /** Records how a connection attempt from {@code sourcePeerId} ended. Can be reported once. */
    @Transactional
    public void reportOutcome(PeerAgentPrincipal agent, UUID sourcePeerId, UUID ticketId, ConnectionOutcome outcome) {
        requireOwnPeer(agent, sourcePeerId);
        ConnectionTicket record = ticketRepository.findByIdAndSourcePeerId(ticketId, sourcePeerId)
                .orElseThrow(ConnectionTicketNotFoundException::new);
        if (record.getOutcome() != null) {
            throw new OutcomeAlreadyReportedException();
        }
        record.recordOutcome(outcome, now());
        log.info("Connection {} from peer {} to peer {} reported outcome {}", ticketId, sourcePeerId,
                record.getTargetPeer().getId(), outcome);
    }

    private static void requireOwnPeer(PeerAgentPrincipal agent, UUID peerId) {
        if (!agent.peerId().equals(peerId)) {
            throw new AccessDeniedException("This credential is not valid for the requested peer");
        }
    }

    private static InvalidConnectionTicketException rejected(UUID targetPeerId, ConnectionTicket record, String reason) {
        log.warn("Rejected connection ticket{} presented to peer {}: {}",
                record == null ? "" : " " + record.getId(), targetPeerId, reason);
        return new InvalidConnectionTicketException();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
