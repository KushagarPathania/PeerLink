package com.peerlink.agent.transfer;

import com.peerlink.agent.protocol.TransferError;
import java.util.Optional;
import java.util.UUID;

/** What the transfer code needs from the coordination server, acting for this agent's own peer. */
public interface TransferCoordinator {

    /** How the server answered a completion report. */
    enum ReportResult {
        /** Recorded (or already recorded) as completed. */
        ACCEPTED,
        /** Definitively refused: the transfer ended otherwise, or this agent may not report it. Do not retry. */
        REFUSED,
        /** The server could not be reached or asked to try again; retrying is safe (completion is idempotent). */
        UNDELIVERED
    }

    /** Registers a file shared from this device; returns the server's file id. */
    UUID registerSharedFile(String fileName, long sizeBytes, String sha256, String contentType, int chunkSizeBytes)
            throws CoordinationException;

    Optional<PendingTransfer> nextTransfer() throws CoordinationException;

    TransferState status(UUID transferId) throws CoordinationException;

    /** Receiver only; the answer says whether to continue. */
    TransferState reportProgress(UUID transferId, long bytesTransferred, int chunksCompleted) throws CoordinationException;

    /** Receiver only: the file was saved after its size and SHA-256 were verified. */
    ReportResult reportCompleted(UUID transferId, long bytesTransferred, String sha256);

    /** Best effort reports: {@code false} if the server could not be told. */
    boolean reportFailed(UUID transferId, TransferError error);

    boolean reportRejected(UUID transferId, TransferError error);
}
