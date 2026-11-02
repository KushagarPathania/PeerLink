package com.peerlink.server.exception;

public class OutcomeAlreadyReportedException extends RuntimeException {

    public OutcomeAlreadyReportedException() {
        super("The outcome of this connection has already been reported.");
    }
}
