package com.peerlink.server.exception;

/**
 * The peer does not exist <em>or</em> belongs to someone else. Both cases look identical to the
 * caller so peer ids of other users cannot be probed.
 */
public class PeerNotFoundException extends RuntimeException {

    public PeerNotFoundException() {
        super("Peer not found.");
    }
}
