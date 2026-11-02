package com.peerlink.server.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A request to copy one shared file from its source peer to a receiving peer. State changes go
 * through the methods below, which enforce the {@link TransferStatus} transition rules, keep
 * progress within bounds and append to the {@link TransferEvent} audit trail.
 */
@Entity
@Table(name = "file_transfers")
public class FileTransfer extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shared_file_id", nullable = false, updatable = false)
    private SharedFile sharedFile;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_peer_id", nullable = false, updatable = false)
    private Peer senderPeer;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receiver_peer_id", nullable = false, updatable = false)
    private Peer receiverPeer;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_id", nullable = false, updatable = false)
    private User requestedBy;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TransferStatus status;

    @PositiveOrZero
    @Column(name = "total_bytes", nullable = false, updatable = false)
    private long totalBytes;

    @PositiveOrZero
    @Column(name = "transferred_bytes", nullable = false)
    private long transferredBytes;

    @PositiveOrZero
    @Column(name = "total_chunks", nullable = false, updatable = false)
    private int totalChunks;

    @PositiveOrZero
    @Column(name = "completed_chunks", nullable = false)
    private int completedChunks;

    @Column(name = "checksum_verified")
    private Boolean checksumVerified;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_code", length = 40)
    private TransferErrorCode errorCode;

    // Sender and receiver both report progress; optimistic locking stops lost updates.
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "transfer", cascade = CascadeType.PERSIST)
    @OrderBy("sequenceNumber ASC")
    private List<TransferEvent> events = new ArrayList<>();

    protected FileTransfer() {
    }

    public FileTransfer(SharedFile sharedFile, Peer receiverPeer, User requestedBy) {
        Objects.requireNonNull(sharedFile, "sharedFile");
        Objects.requireNonNull(receiverPeer, "receiverPeer");
        Objects.requireNonNull(requestedBy, "requestedBy");
        if (!receiverPeer.getUser().equals(requestedBy)) {
            throw new IllegalArgumentException("Files can only be requested to one of the requester's own peers");
        }
        if (receiverPeer.equals(sharedFile.getSourcePeer())) {
            throw new IllegalArgumentException("Sender and receiver must be different peers");
        }
        this.sharedFile = sharedFile;
        this.senderPeer = sharedFile.getSourcePeer();
        this.receiverPeer = receiverPeer;
        this.requestedBy = requestedBy;
        this.totalBytes = sharedFile.getFileSizeBytes();
        this.totalChunks = sharedFile.getTotalChunks();
        this.status = TransferStatus.REQUESTED;
        appendEvent(null, TransferStatus.REQUESTED, null);
    }

    public void authorize() {
        transitionTo(TransferStatus.AUTHORIZED, null);
    }

    public void start(Instant now) {
        transitionTo(TransferStatus.IN_PROGRESS, null);
        this.startedAt = now;
    }

    public void recordProgress(long transferredBytes, int completedChunks) {
        if (status != TransferStatus.IN_PROGRESS) {
            throw new IllegalStateException("Progress can only be recorded while IN_PROGRESS, not " + status);
        }
        if (transferredBytes < this.transferredBytes || transferredBytes > totalBytes) {
            throw new IllegalArgumentException("Transferred bytes must stay between "
                    + this.transferredBytes + " and " + totalBytes + ", got " + transferredBytes);
        }
        if (completedChunks < this.completedChunks || completedChunks > totalChunks) {
            throw new IllegalArgumentException("Completed chunks must stay between "
                    + this.completedChunks + " and " + totalChunks + ", got " + completedChunks);
        }
        this.transferredBytes = transferredBytes;
        this.completedChunks = completedChunks;
    }

    /** Marks the transfer COMPLETED; only valid after the receiver verified the SHA-256 checksum. */
    public void completeWithVerifiedChecksum(Instant now) {
        if (transferredBytes != totalBytes || completedChunks != totalChunks) {
            throw new IllegalStateException("Cannot complete a transfer before all bytes and chunks arrived");
        }
        transitionTo(TransferStatus.COMPLETED, null);
        this.checksumVerified = Boolean.TRUE;
        this.completedAt = now;
    }

    public void failChecksumVerification(Instant now) {
        fail(TransferErrorCode.CHECKSUM_MISMATCH, now);
        this.checksumVerified = Boolean.FALSE;
    }

    public void fail(TransferErrorCode errorCode, Instant now) {
        Objects.requireNonNull(errorCode, "errorCode");
        transitionTo(TransferStatus.FAILED, errorCode);
        this.errorCode = errorCode;
        this.completedAt = now;
    }

    public void cancel(Instant now) {
        transitionTo(TransferStatus.CANCELLED, null);
        this.completedAt = now;
    }

    private void transitionTo(TransferStatus next, TransferErrorCode eventErrorCode) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal transfer status transition " + status + " -> " + next);
        }
        TransferStatus previous = status;
        this.status = next;
        appendEvent(previous, next, eventErrorCode);
    }

    private void appendEvent(TransferStatus from, TransferStatus to, TransferErrorCode eventErrorCode) {
        events.add(new TransferEvent(this, events.size() + 1, from, to, eventErrorCode));
    }

    public SharedFile getSharedFile() {
        return sharedFile;
    }

    public Peer getSenderPeer() {
        return senderPeer;
    }

    public Peer getReceiverPeer() {
        return receiverPeer;
    }

    public User getRequestedBy() {
        return requestedBy;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public long getTransferredBytes() {
        return transferredBytes;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public int getCompletedChunks() {
        return completedChunks;
    }

    public Boolean getChecksumVerified() {
        return checksumVerified;
    }

    public TransferErrorCode getErrorCode() {
        return errorCode;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public List<TransferEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    @Override
    public String toString() {
        return "FileTransfer{id=" + getId() + ", status=" + status
                + ", progress=" + transferredBytes + '/' + totalBytes + '}';
    }
}
