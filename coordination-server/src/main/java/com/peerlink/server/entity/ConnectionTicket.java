package com.peerlink.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Objects;

/**
 * Single-use authorization for one direct TCP connection from a source peer to a target peer.
 * Only the hash of the ticket is stored. The responder proof is held until the target redeems
 * the ticket and is then handed to the target, which proves its identity to the source with it.
 */
@Entity
@Table(name = "connection_tickets")
public class ConnectionTicket extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_peer_id", nullable = false, updatable = false)
    private Peer sourcePeer;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_id", nullable = false, updatable = false)
    private User requestedBy;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_peer_id", nullable = false, updatable = false)
    private Peer targetPeer;

    @Column(name = "ticket_hash", nullable = false, updatable = false, length = 64)
    private String ticketHash;

    @Column(name = "responder_proof", length = 43)
    private String responderProof;

    @Column(name = "target_host", nullable = false, updatable = false)
    private InetAddress targetHost;

    @Column(name = "target_port", nullable = false, updatable = false)
    private int targetPort;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ConnectionTicketStatus status = ConnectionTicketStatus.ISSUED;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 32)
    private ConnectionOutcome outcome;

    @Column(name = "outcome_reported_at")
    private Instant outcomeReportedAt;

    protected ConnectionTicket() {
    }

    public ConnectionTicket(Peer sourcePeer, Peer targetPeer, String ticketHash, String responderProof,
                            Instant issuedAt, Instant expiresAt) {
        Objects.requireNonNull(targetPeer.getHostAddress(), "target peer has no known address");
        if (sourcePeer.equals(targetPeer)) {
            throw new IllegalArgumentException("A peer cannot connect to itself");
        }
        this.sourcePeer = sourcePeer;
        this.requestedBy = sourcePeer.getUser();
        this.targetPeer = targetPeer;
        this.ticketHash = ticketHash;
        this.responderProof = responderProof;
        this.targetHost = targetPeer.getHostAddress();
        this.targetPort = targetPeer.getListeningPort();
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Marks the ticket used and hands out the responder proof exactly once. */
    public String redeem(Instant now) {
        if (status != ConnectionTicketStatus.ISSUED) {
            throw new IllegalStateException("Ticket is " + status);
        }
        String proof = responderProof;
        this.status = ConnectionTicketStatus.REDEEMED;
        this.redeemedAt = now;
        this.responderProof = null;
        return proof;
    }

    public void invalidate() {
        if (status == ConnectionTicketStatus.ISSUED) {
            this.status = ConnectionTicketStatus.INVALIDATED;
            this.responderProof = null;
        }
    }

    /** The source's report of how the attempt went; can be recorded once. */
    public void recordOutcome(ConnectionOutcome outcome, Instant now) {
        if (this.outcome != null) {
            throw new IllegalStateException("Outcome already recorded");
        }
        this.outcome = Objects.requireNonNull(outcome);
        this.outcomeReportedAt = now;
    }

    public Peer getSourcePeer() {
        return sourcePeer;
    }

    public User getRequestedBy() {
        return requestedBy;
    }

    public Peer getTargetPeer() {
        return targetPeer;
    }

    public InetAddress getTargetHost() {
        return targetHost;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public ConnectionTicketStatus getStatus() {
        return status;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRedeemedAt() {
        return redeemedAt;
    }

    public ConnectionOutcome getOutcome() {
        return outcome;
    }

    public Instant getOutcomeReportedAt() {
        return outcomeReportedAt;
    }

    @Override
    public String toString() {
        return "ConnectionTicket{id=" + getId() + ", status=" + status + ", expiresAt=" + expiresAt + '}';
    }
}
