package com.peerlink.server.entity;

/**
 * Controlled vocabulary for transfer failures. Free-form error text (which might leak paths
 * or internal details) is deliberately not persisted. Keep in sync with
 * {@code ck_file_transfers_error_code} in the Flyway migrations.
 */
public enum TransferErrorCode {
    PERMISSION_DENIED,
    TOKEN_REJECTED,
    PEER_UNREACHABLE,
    CONNECTION_LOST,
    FILE_UNAVAILABLE,
    CHECKSUM_MISMATCH,
    STORAGE_ERROR,
    TIMEOUT
}
