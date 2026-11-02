package com.peerlink.agent.protocol;

/** The remote side violated the protocol. {@link #reason()} is what to send back in a REJECT. */
public class ProtocolException extends Exception {

    private final RejectReason reason;

    public ProtocolException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
