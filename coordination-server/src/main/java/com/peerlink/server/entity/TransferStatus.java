package com.peerlink.server.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a file transfer. AUTHORIZED is the "accepted" state: the sending peer's agent has
 * redeemed a ticket bound to this transfer, i.e. it agreed to serve it. Legal transitions:
 * <pre>
 * REQUESTED ──▶ AUTHORIZED ──▶ IN_PROGRESS ──▶ COMPLETED
 *     │              │              │
 *     ├──────────────┴──▶ REJECTED  │      (a peer declined: too large, no space, ...)
 *     └──────────────┴──────────────┴──▶ FAILED | CANCELLED
 * </pre>
 * COMPLETED, REJECTED, FAILED and CANCELLED are terminal.
 */
public enum TransferStatus {
    REQUESTED,
    AUTHORIZED,
    IN_PROGRESS,
    COMPLETED,
    REJECTED,
    FAILED,
    CANCELLED;

    public Set<TransferStatus> allowedNextStatuses() {
        return switch (this) {
            case REQUESTED -> EnumSet.of(AUTHORIZED, REJECTED, FAILED, CANCELLED);
            case AUTHORIZED -> EnumSet.of(IN_PROGRESS, REJECTED, FAILED, CANCELLED);
            case IN_PROGRESS -> EnumSet.of(COMPLETED, FAILED, CANCELLED);
            case COMPLETED, REJECTED, FAILED, CANCELLED -> EnumSet.noneOf(TransferStatus.class);
        };
    }

    public boolean canTransitionTo(TransferStatus next) {
        return allowedNextStatuses().contains(next);
    }

    public boolean isTerminal() {
        return allowedNextStatuses().isEmpty();
    }

    public static Set<TransferStatus> activeStatuses() {
        return EnumSet.of(REQUESTED, AUTHORIZED, IN_PROGRESS);
    }
}
