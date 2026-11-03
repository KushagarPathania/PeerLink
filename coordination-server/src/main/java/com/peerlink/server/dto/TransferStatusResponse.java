package com.peerlink.server.dto;

import com.peerlink.server.entity.TransferStatus;
import java.util.UUID;

/**
 * What an agent needs to know mid-transfer: the status, and whether the transfer is still
 * authorized (sharing not revoked, file still shared, accounts enabled). Agents stop when
 * {@code authorized} is false.
 */
public record TransferStatusResponse(UUID transferId, TransferStatus status, boolean authorized,
                                     long transferredBytes) {
}
