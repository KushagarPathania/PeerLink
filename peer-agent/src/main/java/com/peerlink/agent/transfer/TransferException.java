package com.peerlink.agent.transfer;

import com.peerlink.agent.protocol.TransferError;

/**
 * A transfer cannot continue. {@code reportedByPeer} is true when the other agent said so (it then
 * reports the outcome itself), false when this agent detected the problem.
 */
public class TransferException extends Exception {

    private final TransferError error;
    private final boolean reportedByPeer;

    public TransferException(TransferError error, String message) {
        this(error, message, false);
    }

    public TransferException(TransferError error, String message, boolean reportedByPeer) {
        super(message);
        this.error = error;
        this.reportedByPeer = reportedByPeer;
    }

    public TransferError error() {
        return error;
    }

    public boolean reportedByPeer() {
        return reportedByPeer;
    }
}
