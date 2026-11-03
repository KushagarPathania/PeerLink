package com.peerlink.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peerlink.server.config.PeerPresenceProperties;
import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.PeerStatus;
import com.peerlink.server.repository.PeerRepository;
import com.peerlink.server.security.PeerAgentPrincipal;
import com.peerlink.server.support.RepositoryTestSupport;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

/**
 * Presence rules against PostgreSQL, with a fixed clock so cut-offs are exact. T lies in the past
 * so peers committed by other test classes (with real-time heartbeats) are never stale relative
 * to it and cannot affect the sweep counts.
 */
class PeerPresenceServiceTests extends RepositoryTestSupport {

    private static final Instant T = Instant.parse("2020-01-01T10:00:00Z");
    /** The whole result: peers from other test classes (real-time heartbeats) are online after T as well. */
    private static final PageRequest ALL = PageRequest.of(0, 100_000);
    private static final PeerPresenceProperties POLICY =
            new PeerPresenceProperties(Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(15));

    @Autowired
    private PeerRepository peerRepository;

    @Test
    void staleCutoffIsInclusiveAndExact() {
        Peer peer = fixtures.onlinePeer(fixtures.user(), T);

        // Exactly 60 s after the heartbeat the peer still counts as online...
        assertThat(serviceAt(T.plusSeconds(60)).markStalePeersOffline()).isZero();
        assertThat(reload(peer).getStatus()).isEqualTo(PeerStatus.ONLINE);
        assertThat(reload(peer).isOnline(T.plusSeconds(60), POLICY.staleTimeout())).isTrue();

        // ...one microsecond later it is stale.
        Instant justAfter = T.plusSeconds(60).plusNanos(1_000);
        assertThat(reload(peer).isOnline(justAfter, POLICY.staleTimeout())).isFalse();
        assertThat(serviceAt(justAfter).markStalePeersOffline()).isEqualTo(1);
        assertThat(reload(peer).getStatus()).isEqualTo(PeerStatus.OFFLINE);
    }

    @Test
    void discoveryQueryUsesTheSameCutoff() {
        Peer peer = fixtures.onlinePeer(fixtures.user(), T);
        UUID someoneElse = fixtures.user().getId();
        PeerPresenceService atBoundary = serviceAt(T.plusSeconds(60));
        PeerPresenceService justAfter = serviceAt(T.plusSeconds(60).plusNanos(1_000));

        assertThat(peerRepository.findOnlinePeersExcludingUser(
                atBoundary.onlineCutoff(atBoundary.now()), someoneElse, ALL)).contains(peer);
        assertThat(peerRepository.findOnlinePeersExcludingUser(
                justAfter.onlineCutoff(justAfter.now()), someoneElse, ALL)).doesNotContain(peer);
    }

    @Test
    void sweepTouchesOnlyStaleOnlineRows() {
        fixtures.onlinePeer(fixtures.user(), T.minus(Duration.ofMinutes(10)));
        fixtures.onlinePeer(fixtures.user(), T);
        fixtures.peer(fixtures.user()); // already offline

        assertThat(serviceAt(T.plusSeconds(30)).markStalePeersOffline()).isEqualTo(1);
        assertThat(serviceAt(T.plusSeconds(30)).markStalePeersOffline()).isZero();
    }

    @Test
    void heartbeatRecordsServerTimeAndObservedAddress() throws Exception {
        Peer peer = fixtures.peer(fixtures.user());
        PeerAgentPrincipal agent = new PeerAgentPrincipal(peer.getId(), peer.getUser().getId());

        var response = serviceAt(T).recordHeartbeat(peer.getId(), agent, InetAddress.getByName("fe80::1%1"));

        assertThat(response.serverTime()).isEqualTo(T);
        assertThat(response.status()).isEqualTo(PeerStatus.ONLINE);
        Peer reloaded = reload(peer);
        assertThat(reloaded.getLastHeartbeatAt()).isEqualTo(T);
        assertThat(reloaded.getHostAddress()).isEqualTo(InetAddress.getByName("fe80::1")); // zone id stripped
    }

    @Test
    void heartbeatForAnotherPeerIsDenied() {
        Peer peer = fixtures.peer(fixtures.user());
        PeerAgentPrincipal otherAgent = new PeerAgentPrincipal(UUID.randomUUID(), peer.getUser().getId());

        assertThatThrownBy(() -> serviceAt(T).recordHeartbeat(peer.getId(), otherAgent, InetAddress.getLoopbackAddress()))
                .isInstanceOf(AccessDeniedException.class);
    }

    private PeerPresenceService serviceAt(Instant now) {
        return new PeerPresenceService(peerRepository, POLICY, Clock.fixed(now, ZoneOffset.UTC));
    }

    private Peer reload(Peer peer) {
        entityManager.flush();
        entityManager.clear();
        return peerRepository.findById(peer.getId()).orElseThrow();
    }
}
