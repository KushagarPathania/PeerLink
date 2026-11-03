package com.peerlink.agent.transfer;

/** The coordination server could not be reached or did not accept a request. Never contains secrets. */
public class CoordinationException extends Exception {

    private final int status;

    public CoordinationException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** HTTP status, or 0 if the server was not reached. */
    public int status() {
        return status;
    }

    public boolean credentialRejected() {
        return status == 401;
    }
}
