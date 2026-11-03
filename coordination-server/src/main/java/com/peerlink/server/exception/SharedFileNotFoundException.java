package com.peerlink.server.exception;

/** The file does not exist, is no longer shared, or the caller may not access it; all look identical. */
public class SharedFileNotFoundException extends RuntimeException {

    public SharedFileNotFoundException() {
        super("File not found.");
    }
}
