package com.peerlink.agent.security;

/** A ticket could not be obtained or verified. Messages never contain ticket values. */
public class TicketException extends Exception {

    public enum Reason {
        /** The target peer does not exist, is offline, or may not be contacted. */
        TARGET_UNAVAILABLE,
        /** The presented ticket is unknown, expired, used, revoked or meant for another peer. */
        INVALID_TICKET,
        /** This agent's own credential was rejected. */
        CREDENTIAL_REJECTED,
        /** The coordination server could not be reached or failed. */
        SERVER_UNAVAILABLE,
        /** The server's answer could not be understood. */
        INVALID_RESPONSE
    }

    private final Reason reason;

    public TicketException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
