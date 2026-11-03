package com.peerlink.agent.protocol;

import java.util.Set;

/** Message types of protocol version 2, each with the only fields it may carry (besides {@code type}). */
public enum MessageType {
    HELLO(Set.of("versions", "peerId", "ticket")),
    HELLO_ACK(Set.of("version", "peerId", "proof")),
    REJECT(Set.of("reason")),
    PING(Set.of("nonce")),
    PONG(Set.of("nonce")),
    BYE(Set.of("reason")),
    /** Receiver → sender: send me the transfer my ticket authorizes. */
    TRANSFER_REQUEST(Set.of("transferId")),
    /** Sender → receiver: file metadata; the receiver checks it against the server's. */
    TRANSFER_OFFER(Set.of("transferId", "fileName", "size", "sha256", "chunkSize")),
    /** Receiver → sender: ready, start sending data frames. */
    TRANSFER_ACCEPT(Set.of("transferId")),
    /** Either side, before data flows: declined, with a reason. */
    TRANSFER_REJECT(Set.of("transferId", "reason")),
    /** Receiver → sender: bytes received so far (every few chunks, never per chunk). */
    TRANSFER_ACK(Set.of("transferId", "bytes")),
    /** Sender → receiver: all data sent; what was sent. */
    TRANSFER_END(Set.of("transferId", "size", "sha256")),
    /** Receiver → sender: verified and saved. */
    TRANSFER_COMPLETE(Set.of("transferId", "size", "sha256")),
    /** Either side: the transfer failed, with a reason. */
    TRANSFER_FAILED(Set.of("transferId", "reason")),
    /** Either side: the transfer was cancelled or is no longer authorized. */
    TRANSFER_CANCEL(Set.of("transferId", "reason"));

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
