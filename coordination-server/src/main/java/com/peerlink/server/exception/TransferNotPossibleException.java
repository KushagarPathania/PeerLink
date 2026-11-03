package com.peerlink.server.exception;

/** A transfer cannot be requested right now (sender offline, duplicate request, ...). */
public class TransferNotPossibleException extends RuntimeException {

    public TransferNotPossibleException(String message) {
        super(message);
    }
}
