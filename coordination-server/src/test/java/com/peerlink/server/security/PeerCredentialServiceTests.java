package com.peerlink.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.peerlink.server.dto.AgentCredentialResponse;
import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.User;
import com.peerlink.server.repository.PeerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PeerCredentialServiceTests {

    private final PeerRepository peerRepository = mock(PeerRepository.class);
    private final PeerCredentialService service = new PeerCredentialService(peerRepository,
            Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC));
    private final User owner = new User("alice", "alice@example.com", "hash");

    @Test
    void issuesUniqueHighEntropyCredentialsAndKeepsOnlyTheHash() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            Peer peer = new Peer(owner, "laptop", 9090);
            AgentCredentialResponse issued = service.issue(peer);
            assertThat(issued.credential()).matches("plk_[A-Za-z0-9_-]{43}");
            assertThat(peer.hasActiveCredential()).isTrue();
            assertThat(seen.add(issued.credential())).isTrue();
        }
    }

    @Test
    void credentialNeverAppearsInToString() {
        AgentCredentialResponse issued = service.issue(new Peer(owner, "laptop", 9090));

        assertThat(issued.toString()).doesNotContain(issued.credential()).contains("****");
    }

    @Test
    void hashIsSha256Hex() {
        assertThat(PeerCredentialService.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void malformedCredentialsAreRejectedWithoutDatabaseLookup() {
        assertThat(service.authenticate(null)).isEmpty();
        assertThat(service.authenticate("not-a-peerlink-credential")).isEmpty();
        assertThat(service.authenticate("plk_" + "x".repeat(200))).isEmpty();
        verify(peerRepository, never()).findByCredentialHash(any());
    }

    @Test
    void credentialOfDisabledOwnerIsRejected() {
        Peer peer = new Peer(owner, "laptop", 9090);
        String credential = service.issue(peer).credential();
        given(peerRepository.findByCredentialHash(PeerCredentialService.hash(credential))).willReturn(Optional.of(peer));

        assertThat(service.authenticate(credential)).isPresent();
        owner.disable();
        assertThat(service.authenticate(credential)).isEmpty();
    }
}
