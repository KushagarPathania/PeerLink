package com.peerlink.agent.protocol;

/** Why a connection or message was refused; sent in REJECT and BYE messages. */
public enum RejectReason {
    MALFORMED,
    FRAME_TOO_LARGE,
    UNKNOWN_MESSAGE_TYPE,
    UNEXPECTED_MESSAGE,
    UNSUPPORTED_VERSION,
    UNAUTHORIZED,
    BUSY,
    UNAVAILABLE,
    TOO_MANY_MESSAGES,
    INTERNAL_ERROR;

    /** Parses a reason received from a peer; unknown values are reported as MALFORMED. */
    public static RejectReason parse(String value) {
        for (RejectReason reason : values()) {
            if (reason.name().equals(value)) {
                return reason;
            }
        }
        return MALFORMED;
    }
}
