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
import jakarta.validation.constraints.Positive;

/** One immutable entry in a transfer's status history. Created only by {@link FileTransfer}. */
@Entity
@Table(name = "transfer_events")
public class TransferEvent extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transfer_id", nullable = false, updatable = false)
    private FileTransfer transfer;

    @Positive
    @Column(name = "sequence_number", nullable = false, updatable = false)
    private int sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 16, updatable = false)
    private TransferStatus fromStatus;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 16, updatable = false)
    private TransferStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_code", length = 40, updatable = false)
    private TransferErrorCode errorCode;

    /** The peer whose agent reported this change; {@code null} for changes made by users or the server. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by_peer_id", updatable = false)
    private Peer reportedByPeer;

    protected TransferEvent() {
    }

    TransferEvent(FileTransfer transfer, int sequenceNumber, TransferStatus fromStatus,
                  TransferStatus toStatus, TransferErrorCode errorCode, Peer reportedByPeer) {
        this.transfer = transfer;
        this.sequenceNumber = sequenceNumber;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.errorCode = errorCode;
        this.reportedByPeer = reportedByPeer;
    }

    public FileTransfer getTransfer() {
        return transfer;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public TransferStatus getFromStatus() {
        return fromStatus;
    }

    public TransferStatus getToStatus() {
        return toStatus;
    }

    public TransferErrorCode getErrorCode() {
        return errorCode;
    }

    public Peer getReportedByPeer() {
        return reportedByPeer;
    }

    @Override
    public String toString() {
        return "TransferEvent{#" + sequenceNumber + ' ' + fromStatus + " -> " + toStatus + '}';
    }
}
