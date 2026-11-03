package com.peerlink.server.exception;

/** The transfer does not exist or the caller may not see it; both look identical. */
public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException() {
        super("Transfer not found.");
    }
}
