package com.peerlink.agent.security;

import java.util.UUID;

/**
 * The one file transfer a redeemed ticket authorizes, as stated by the coordination server. The
 * sending agent serves exactly this file and never anything named by the connecting peer.
 */
public record TransferGrant(UUID transferId, UUID sharedFileId, String fileName, long fileSizeBytes,
                            String sha256Checksum, int chunkSizeBytes) {
}
