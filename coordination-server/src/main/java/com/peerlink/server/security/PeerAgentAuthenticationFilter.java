package com.peerlink.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates peer agents that send {@code Authorization: Bearer <credential>}. When the header
 * is present it is the <em>only</em> source of identity for the request: an invalid credential is
 * rejected with 401 and never falls back to a session cookie. The authentication is not stored in
 * the session, so each agent request stands alone.
 */
public class PeerAgentAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final PeerCredentialService credentialService;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    public PeerAgentAuthenticationFilter(PeerCredentialService credentialService,
                                         AuthenticationEntryPoint authenticationEntryPoint) {
        this.credentialService = credentialService;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    /** Requests carrying a bearer credential; CSRF protection is not needed for them (see SecurityConfig). */
    public static boolean hasBearerCredential(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!hasBearerCredential(request)) {
            chain.doFilter(request, response);
            return;
        }
        String credential = request.getHeader(HttpHeaders.AUTHORIZATION).substring(BEARER_PREFIX.length()).trim();
        Optional<PeerAgentPrincipal> principal = credentialService.authenticate(credential);
        if (principal.isEmpty()) {
            securityContextHolderStrategy.clearContext();
            authenticationEntryPoint.commence(request, response,
                    new BadCredentialsException("Invalid or revoked peer-agent credential"));
            return;
        }
        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(new PeerAgentAuthenticationToken(principal.get()));
        securityContextHolderStrategy.setContext(context);
        chain.doFilter(request, response);
    }
}
