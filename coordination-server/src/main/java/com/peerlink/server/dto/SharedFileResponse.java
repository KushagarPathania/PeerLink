package com.peerlink.server.dto;

import com.peerlink.server.entity.SharedFile;
import java.time.Instant;
import java.util.UUID;

public record SharedFileResponse(UUID id, String ownerUsername, UUID sourcePeerId, String fileName,
                                 long fileSizeBytes, String sha256Checksum, String contentType, int chunkSizeBytes,
                                 int totalChunks, boolean available, Instant createdAt) {

    public static SharedFileResponse from(SharedFile file) {
        return new SharedFileResponse(file.getId(), file.getOwner().getUsername(), file.getSourcePeer().getId(),
                file.getFileName(), file.getFileSizeBytes(), file.getSha256Checksum(), file.getContentType(),
                file.getChunkSizeBytes(), file.getTotalChunks(), file.isAvailable(), file.getCreatedAt());
    }
}
