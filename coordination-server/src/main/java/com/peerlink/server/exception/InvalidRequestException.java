package com.peerlink.server.exception;

/** A request that is well-formed but not acceptable, reported like a field validation error. */
public class InvalidRequestException extends RuntimeException {

    private final String field;

    public InvalidRequestException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
