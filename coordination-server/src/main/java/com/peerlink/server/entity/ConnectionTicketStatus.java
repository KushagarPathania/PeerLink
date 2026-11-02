package com.peerlink.server.entity;

public enum ConnectionTicketStatus {
    /** Issued to the source peer; can be redeemed once by the target peer before it expires. */
    ISSUED,
    /** Redeemed by the target peer. */
    REDEEMED,
    /** Can no longer be redeemed: misused (wrong target) or its source credential was revoked. */
    INVALIDATED
}
