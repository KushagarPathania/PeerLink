package com.peerlink.server.dto;

/** Result of registering a device: the peer and its one-time agent credential. */
public record PeerRegistrationResponse(PeerResponse peer, AgentCredentialResponse agentCredential) {
}
