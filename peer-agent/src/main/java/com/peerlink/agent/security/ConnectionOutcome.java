package com.peerlink.agent.security;

/** Outcome of a connection attempt, as reported to the coordination server. Mirrors the server's enum. */
public enum ConnectionOutcome {
    SUCCEEDED,
    CONNECT_FAILED,
    TIMEOUT,
    REJECTED_BY_PEER,
    PROTOCOL_ERROR,
    PEER_IDENTITY_MISMATCH
}
