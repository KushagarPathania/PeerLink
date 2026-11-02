package com.peerlink.server.entity;

/** Result of a connection attempt as reported by the source peer. Keep in sync with V4's check constraint. */
public enum ConnectionOutcome {
    SUCCEEDED,
    CONNECT_FAILED,
    TIMEOUT,
    REJECTED_BY_PEER,
    PROTOCOL_ERROR,
    PEER_IDENTITY_MISMATCH
}
