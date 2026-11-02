package com.peerlink.server.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.PeerStatus;
import com.peerlink.server.entity.User;
import com.peerlink.server.support.RepositoryTestSupport;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

class PeerRepositoryTests extends RepositoryTestSupport {

    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(90);

    @Autowired
    private PeerRepository peerRepository;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Test
    void findsPeersBelongingToUser() {
        User alice = fixtures.user();
        User bob = fixtures.user();
        Peer laptop = entityManager.persistAndFlush(new Peer(alice, "laptop", InetAddress.getLoopbackAddress(), 9090));
        Peer desktop = entityManager.persistAndFlush(new Peer(alice, "desktop", InetAddress.getLoopbackAddress(), 9091));
        fixtures.peer(bob);

        assertThat(peerRepository.findByUserOrderByDeviceNameAsc(alice)).containsExactly(desktop, laptop);
        assertThat(peerRepository.findByIdAndUser(laptop.getId(), alice)).contains(laptop);
        assertThat(peerRepository.findByIdAndUser(laptop.getId(), bob)).isEmpty();
        assertThat(peerRepository.findByUserAndDeviceName(alice, "laptop")).contains(laptop);
    }

    @Test
    void discoveryReturnsOnlyRecentlySeenPeersOfOtherEnabledUsers() {
        User me = fixtures.user();
        User other = fixtures.user();
        User disabled = fixtures.user();
        disabled.disable();

        Peer fresh = fixtures.onlinePeer(other, now.minusSeconds(10));
        fixtures.onlinePeer(other, now.minus(HEARTBEAT_TIMEOUT).minusSeconds(1)); // stale heartbeat
        fixtures.peer(other);                                                     // never online
        fixtures.onlinePeer(me, now);                                             // own device
        fixtures.onlinePeer(disabled, now);                                       // disabled account

        Page<Peer> page = peerRepository.findOnlinePeersExcludingUser(
                now.minus(HEARTBEAT_TIMEOUT), me.getId(), PageRequest.of(0, 10));

        assertThat(page.getContent()).containsExactly(fresh);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void marksPeersWithStaleHeartbeatsOffline() {
        User user = fixtures.user();
        Peer stale = fixtures.onlinePeer(user, now.minus(Duration.ofMinutes(5)));
        Peer fresh = fixtures.onlinePeer(user, now);
        Instant cutoff = now.minus(HEARTBEAT_TIMEOUT);

        assertThat(peerRepository.findByStatusAndLastHeartbeatAtBefore(PeerStatus.ONLINE, cutoff)).containsExactly(stale);

        int updated = peerRepository.markStalePeersOffline(cutoff, now);

        assertThat(updated).isEqualTo(1);
        assertThat(peerRepository.findById(stale.getId())).get().extracting(Peer::getStatus).isEqualTo(PeerStatus.OFFLINE);
        assertThat(peerRepository.findById(fresh.getId())).get().extracting(Peer::getStatus).isEqualTo(PeerStatus.ONLINE);
    }

    @Test
    void storesIpv6AddressesAsInet() throws Exception {
        User user = fixtures.user();
        InetAddress ipv6 = InetAddress.getByName("fe80::1");
        Peer peer = entityManager.persistAndFlush(new Peer(user, "ipv6-box", ipv6, 9090));
        entityManager.clear();

        assertThat(peerRepository.findById(peer.getId())).get().extracting(Peer::getHostAddress).isEqualTo(ipv6);
    }

    @Test
    void rejectsDuplicateDeviceNameForSameUser() {
        User user = fixtures.user();
        peerRepository.saveAndFlush(new Peer(user, "phone", InetAddress.getLoopbackAddress(), 9090));

        assertThatThrownBy(() -> peerRepository.saveAndFlush(new Peer(user, "phone", InetAddress.getLoopbackAddress(), 9091)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_peers_user_device_name");
    }

    @Test
    void databaseRejectsPortAboveRange() {
        Peer peer = fixtures.peer(fixtures.user());

        assertSqlViolates("ck_peers_listening_port", "update peers set listening_port = 65536 where id = ?", peer.getId());
    }

    @Test
    void databaseRejectsPortZero() {
        Peer peer = fixtures.peer(fixtures.user());

        assertSqlViolates("ck_peers_listening_port", "update peers set listening_port = 0 where id = ?", peer.getId());
    }

    @Test
    void databaseRejectsOnlinePeerWithoutHeartbeat() {
        Peer peer = fixtures.peer(fixtures.user());

        assertSqlViolates("ck_peers_online_has_heartbeat", "update peers set status = 'ONLINE' where id = ?", peer.getId());
    }

    @Test
    void databaseRejectsMalformedHostAddress() {
        Peer peer = fixtures.peer(fixtures.user());

        assertSqlViolates("inet", "update peers set host_address = 'not-an-address' where id = ?", peer.getId());
    }

    @Test
    void newPeerHasNoAddressUntilFirstHeartbeat() {
        Peer peer = entityManager.persistAndFlush(new Peer(fixtures.user(), "fresh", 9090));
        entityManager.clear();

        Peer loaded = peerRepository.findById(peer.getId()).orElseThrow();
        assertThat(loaded.getHostAddress()).isNull();
        assertThat(loaded.getStatus()).isEqualTo(PeerStatus.OFFLINE);
    }

    @Test
    void findsPeerByCredentialHashWithOwner() {
        Peer peer = fixtures.peer(fixtures.user());
        peer.assignCredential("a".repeat(64), now);
        entityManager.flush();
        entityManager.clear();

        assertThat(peerRepository.findByCredentialHash("a".repeat(64))).get().satisfies(found -> {
            assertThat(found.getId()).isEqualTo(peer.getId());
            assertThat(found.getUser().getUsername()).isEqualTo(peer.getUser().getUsername());
        });
        assertThat(peerRepository.findByCredentialHash("b".repeat(64))).isEmpty();
    }

    @Test
    void databaseRejectsOnlinePeerWithoutAddress() {
        Peer peer = fixtures.onlinePeer(fixtures.user(), now);

        assertSqlViolates("ck_peers_online_has_address", "update peers set host_address = null where id = ?", peer.getId());
    }

    @Test
    void databaseRejectsMalformedCredentialHash() {
        Peer peer = fixtures.peer(fixtures.user());

        assertSqlViolates("ck_peers_credential_hash_format",
                "update peers set credential_hash = 'plaintext-secret', credential_issued_at = now() where id = ?",
                peer.getId());
    }

    @Test
    void databaseRequiresIssueTimeWithCredentialHash() {
        Peer peer = fixtures.peer(fixtures.user());

        assertSqlViolates("ck_peers_credential_issued",
                "update peers set credential_hash = ? where id = ?", "c".repeat(64), peer.getId());
    }

    @Test
    void databaseRejectsPeerForUnknownUser() {
        assertSqlViolates("fk_peers_user",
                "insert into peers (user_id, device_name, host_address, listening_port) values (?, 'ghost', '10.0.0.1', 9090)",
                UUID.randomUUID());
    }

    @Test
    void deletingUserWithPeersIsRestricted() {
        User user = fixtures.user();
        fixtures.peer(user);

        assertSqlViolates("fk_peers_user", "delete from users where id = ?", user.getId());
    }
}
