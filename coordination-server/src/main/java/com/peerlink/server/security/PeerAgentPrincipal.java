package com.peerlink.server.security;

import java.security.Principal;
import java.util.UUID;

/** A peer agent authenticated by its device credential. It acts for exactly one peer. */
public record PeerAgentPrincipal(UUID peerId, UUID ownerId) implements Principal {

    @Override
    public String getName() {
        return "peer:" + peerId;
    }
}
