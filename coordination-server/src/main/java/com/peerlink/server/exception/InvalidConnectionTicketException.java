package com.peerlink.server.exception;

/**
 * A ticket was presented that is unknown, expired, already used, revoked or meant for another
 * peer. All of these look identical to the caller so the response is no oracle; the server log
 * records the actual reason.
 */
public class InvalidConnectionTicketException extends RuntimeException {

    public InvalidConnectionTicketException() {
        super("The connection ticket is invalid, expired or already used.");
    }
}
