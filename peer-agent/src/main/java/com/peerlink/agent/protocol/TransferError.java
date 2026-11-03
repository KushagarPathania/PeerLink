package com.peerlink.agent.protocol;

/**
 * Why a transfer did not succeed. Used in TRANSFER_REJECT/FAILED/CANCEL messages and, except for
 * {@link #CANCELLED}, reported to the coordination server under the same name.
 */
public enum TransferError {
    PERMISSION_DENIED,
    PEER_BUSY,
    PEER_UNREACHABLE,
    FILE_UNAVAILABLE,
    FILE_CHANGED,
    FILE_TOO_LARGE,
    INSUFFICIENT_STORAGE,
    METADATA_MISMATCH,
    CHECKSUM_MISMATCH,
    STORAGE_ERROR,
    PROTOCOL_ERROR,
    CONNECTION_LOST,
    TIMEOUT,
    AGENT_SHUTDOWN,
    SERVER_UNAVAILABLE,
    /** The user cancelled; the transfer status says so, it is not an error code. */
    CANCELLED;

    /** Parses a reason received from a peer; unknown values count as a protocol error. */
    public static TransferError parse(String value) {
        for (TransferError error : values()) {
            if (error.name().equals(value)) {
                return error;
            }
        }
        return PROTOCOL_ERROR;
    }

    public boolean reportable() {
        return this != CANCELLED;
    }
}
