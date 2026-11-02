package com.peerlink.server.security;

import com.peerlink.server.dto.AgentCredentialResponse;
import com.peerlink.server.entity.Peer;
import com.peerlink.server.repository.PeerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and verifies peer-agent credentials. A credential is {@code plk_} followed by 256 random
 * bits (base64url). Only its SHA-256 hash is stored: because the secret is random and long, a
 * fast hash is sufficient (unlike passwords, it cannot be guessed from a dictionary), and it lets
 * the credential be looked up directly through a unique index.
 */
@Service
public class PeerCredentialService {

    public static final String CREDENTIAL_PREFIX = "plk_";
    private static final int MAX_CREDENTIAL_LENGTH = 128;

    private final PeerRepository peerRepository;
    private final Clock clock;

    public PeerCredentialService(PeerRepository peerRepository, Clock clock) {
        this.peerRepository = peerRepository;
        this.clock = clock;
    }

    /**
     * Generates a new credential for a persisted peer, replacing any previous one. The returned
     * plaintext must be shown to the user once and never stored or logged.
     */
    public AgentCredentialResponse issue(Peer peer) {
        String credential = CREDENTIAL_PREFIX + SecretTokens.randomValue();
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        peer.assignCredential(hash(credential), issuedAt);
        return new AgentCredentialResponse(peer.getId(), credential, issuedAt);
    }

    /** Resolves a presented credential to its peer, provided it is current and the owner is enabled. */
    @Transactional(readOnly = true)
    public Optional<PeerAgentPrincipal> authenticate(String credential) {
        if (credential == null || !credential.startsWith(CREDENTIAL_PREFIX)
                || credential.length() > MAX_CREDENTIAL_LENGTH) {
            return Optional.empty();
        }
        return peerRepository.findByCredentialHash(hash(credential))
                .filter(peer -> peer.getUser().isEnabled())
                .map(peer -> new PeerAgentPrincipal(peer.getId(), peer.getUser().getId()));
    }

    static String hash(String credential) {
        return SecretTokens.sha256Hex(credential);
    }
}
