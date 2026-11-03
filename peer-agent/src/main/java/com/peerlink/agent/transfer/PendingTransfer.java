package com.peerlink.agent.transfer;

import java.util.UUID;

/** A requested transfer this (receiving) device should fetch, with the server's metadata for the file. */
public record PendingTransfer(UUID transferId, UUID sharedFileId, UUID senderPeerId, String fileName,
                              long fileSizeBytes, String sha256Checksum, int chunkSizeBytes, int totalChunks) {
}
