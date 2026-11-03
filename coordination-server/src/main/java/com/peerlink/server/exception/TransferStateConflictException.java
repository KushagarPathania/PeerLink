package com.peerlink.server.exception;

/** The requested change is not possible in the transfer's current state (e.g. it already finished). */
public class TransferStateConflictException extends RuntimeException {

    public TransferStateConflictException(String message) {
        super(message);
    }
}
