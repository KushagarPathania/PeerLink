package com.peerlink.agent.transfer;

/** The server's view of a transfer: its status and whether it is still authorized. */
public record TransferState(String status, boolean authorized) {

    public boolean active() {
        return authorized && (status.equals("REQUESTED") || status.equals("AUTHORIZED") || status.equals("IN_PROGRESS"));
    }

    public boolean cancelled() {
        return status.equals("CANCELLED");
    }
}
