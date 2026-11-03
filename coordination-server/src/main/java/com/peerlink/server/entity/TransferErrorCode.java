package com.peerlink.server.entity;

/**
 * Controlled vocabulary for transfer failures. Free-form error text (which might leak paths
 * or internal details) is deliberately not persisted. Keep in sync with
 * {@code ck_file_transfers_error_code} in the Flyway migrations (latest: V5).
 */
public enum TransferErrorCode {
    PERMISSION_DENIED,
    TOKEN_REJECTED,
    PEER_UNREACHABLE,
    CONNECTION_LOST,
    FILE_UNAVAILABLE,
    CHECKSUM_MISMATCH,
    STORAGE_ERROR,
    TIMEOUT,
    /** The sender is already serving its maximum number of transfers. */
    PEER_BUSY,
    /** Larger than the receiver accepts. */
    FILE_TOO_LARGE,
    /** Not enough free space at the receiver. */
    INSUFFICIENT_STORAGE,
    /** The shared file changed or disappeared since it was registered. */
    FILE_CHANGED,
    /** What the sender offered did not match the server's metadata. */
    METADATA_MISMATCH,
    PROTOCOL_ERROR,
    /** An agent stopped during the transfer. */
    AGENT_SHUTDOWN,
    /** The receiving peer never picked the request up. */
    REQUEST_EXPIRED,
    /** The coordination server could not be reached to confirm authorization. */
    SERVER_UNAVAILABLE
}
