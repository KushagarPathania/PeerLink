package com.peerlink.server.exception;

public class ConnectionTicketNotFoundException extends RuntimeException {

    public ConnectionTicketNotFoundException() {
        super("Connection not found.");
    }
}
