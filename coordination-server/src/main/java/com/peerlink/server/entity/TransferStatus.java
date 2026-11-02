package com.peerlink.server.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a file transfer. Legal transitions:
 * <pre>
 * REQUESTED ──▶ AUTHORIZED ──▶ IN_PROGRESS ──▶ COMPLETED
 *     │              │              │
 *     └──────────────┴──────────────┴──▶ FAILED | CANCELLED
 * </pre>
 * COMPLETED, FAILED and CANCELLED are terminal.
 */
public enum TransferStatus {
    REQUESTED,
    AUTHORIZED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED;

    public Set<TransferStatus> allowedNextStatuses() {
        return switch (this) {
            case REQUESTED -> EnumSet.of(AUTHORIZED, FAILED, CANCELLED);
            case AUTHORIZED -> EnumSet.of(IN_PROGRESS, FAILED, CANCELLED);
            case IN_PROGRESS -> EnumSet.of(COMPLETED, FAILED, CANCELLED);
            case COMPLETED, FAILED, CANCELLED -> EnumSet.noneOf(TransferStatus.class);
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
