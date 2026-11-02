package com.peerlink.agent.protocol;

import java.util.Set;

/** Message types of protocol version 1, each with the only fields it may carry (besides {@code type}). */
public enum MessageType {
    HELLO(Set.of("versions", "peerId", "ticket")),
    HELLO_ACK(Set.of("version", "peerId", "proof")),
    REJECT(Set.of("reason")),
    PING(Set.of("nonce")),
    PONG(Set.of("nonce")),
    BYE(Set.of("reason"));

    private final Set<String> allowedFields;

    MessageType(Set<String> allowedFields) {
        this.allowedFields = allowedFields;
    }

    public Set<String> allowedFields() {
        return allowedFields;
    }

    static MessageType parse(String value) throws ProtocolException {
        for (MessageType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        throw new ProtocolException(RejectReason.UNKNOWN_MESSAGE_TYPE, "Unknown message type");
    }
}
