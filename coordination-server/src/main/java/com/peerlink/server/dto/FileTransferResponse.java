package com.peerlink.server.dto;

import com.peerlink.server.entity.FileTransfer;
import com.peerlink.server.entity.TransferErrorCode;
import com.peerlink.server.entity.TransferStatus;
import java.time.Instant;
import java.util.UUID;

/** Transfer summary for history views. Contains no file contents, paths or authorization tokens. */
public record FileTransferResponse(UUID id, UUID sharedFileId, String fileName, UUID senderPeerId,
                                   UUID receiverPeerId, String requestedByUsername, TransferStatus status,
                                   long totalBytes, long transferredBytes, int totalChunks, int completedChunks,
                                   Boolean checksumVerified, TransferErrorCode errorCode, Instant createdAt,
                                   Instant startedAt, Instant completedAt) {

    public static FileTransferResponse from(FileTransfer transfer) {
        return new FileTransferResponse(transfer.getId(), transfer.getSharedFile().getId(),
                transfer.getSharedFile().getFileName(), transfer.getSenderPeer().getId(),
                transfer.getReceiverPeer().getId(), transfer.getRequestedBy().getUsername(), transfer.getStatus(),
                transfer.getTotalBytes(), transfer.getTransferredBytes(), transfer.getTotalChunks(),
                transfer.getCompletedChunks(), transfer.getChecksumVerified(), transfer.getErrorCode(),
                transfer.getCreatedAt(), transfer.getStartedAt(), transfer.getCompletedAt());
    }
}
