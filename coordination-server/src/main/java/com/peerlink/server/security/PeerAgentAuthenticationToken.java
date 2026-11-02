package com.peerlink.server.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Authentication for a request signed with a peer-agent credential. It carries only
 * {@code ROLE_PEER_AGENT}, which grants access to the heartbeat endpoint and nothing else.
 */
public class PeerAgentAuthenticationToken extends AbstractAuthenticationToken {

    public static final String ROLE_PEER_AGENT = "ROLE_PEER_AGENT";

    private final PeerAgentPrincipal principal;

    public PeerAgentAuthenticationToken(PeerAgentPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority(ROLE_PEER_AGENT)));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null; // the credential is never kept after verification
    }

    @Override
    public PeerAgentPrincipal getPrincipal() {
        return principal;
    }
}
