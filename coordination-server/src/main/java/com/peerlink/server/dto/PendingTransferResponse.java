package com.peerlink.server.dto;

import com.peerlink.server.entity.FileTransfer;
import java.util.UUID;

/**
 * A requested transfer the receiving agent should fetch, with the metadata it will check the
 * sender's offer against. Flat for the agent's JSON reader.
 */
public record PendingTransferResponse(UUID transferId, UUID sharedFileId, UUID senderPeerId, String fileName,
                                      long fileSizeBytes, String sha256Checksum, int chunkSizeBytes, int totalChunks) {

    public static PendingTransferResponse from(FileTransfer transfer) {
        return new PendingTransferResponse(transfer.getId(), transfer.getSharedFile().getId(),
                transfer.getSenderPeer().getId(), transfer.getSharedFile().getFileName(), transfer.getTotalBytes(),
                transfer.getSharedFile().getSha256Checksum(), transfer.getSharedFile().getChunkSizeBytes(),
                transfer.getTotalChunks());
    }
}
