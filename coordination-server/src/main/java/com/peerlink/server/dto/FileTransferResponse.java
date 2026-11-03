package com.peerlink.server.dto;

import com.peerlink.server.entity.FileTransfer;
import com.peerlink.server.entity.TransferErrorCode;
import com.peerlink.server.entity.TransferStatus;
import java.time.Instant;
import java.util.UUID;

/** Transfer summary for history views. Contains no file contents, paths, tickets or credentials. */
public record FileTransferResponse(UUID id, UUID sharedFileId, String fileName, String fileOwnerUsername,
                                   UUID senderPeerId, String senderDeviceName, UUID receiverPeerId,
                                   String receiverDeviceName, String requestedByUsername, TransferStatus status,
                                   long totalBytes, long transferredBytes, int totalChunks, int completedChunks,
                                   Boolean checksumVerified, TransferErrorCode errorCode, Instant createdAt,
                                   Instant startedAt, Instant completedAt) {

    public static FileTransferResponse from(FileTransfer transfer) {
        return new FileTransferResponse(transfer.getId(), transfer.getSharedFile().getId(),
                transfer.getSharedFile().getFileName(), transfer.getSharedFile().getOwner().getUsername(),
                transfer.getSenderPeer().getId(), transfer.getSenderPeer().getDeviceName(),
                transfer.getReceiverPeer().getId(), transfer.getReceiverPeer().getDeviceName(),
                transfer.getRequestedBy().getUsername(), transfer.getStatus(), transfer.getTotalBytes(),
                transfer.getTransferredBytes(), transfer.getTotalChunks(), transfer.getCompletedChunks(),
                transfer.getChecksumVerified(), transfer.getErrorCode(), transfer.getCreatedAt(),
                transfer.getStartedAt(), transfer.getCompletedAt());
    }
}
