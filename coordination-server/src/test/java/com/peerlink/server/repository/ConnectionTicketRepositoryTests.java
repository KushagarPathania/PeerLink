package com.peerlink.server.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.peerlink.server.entity.ConnectionTicket;
import com.peerlink.server.entity.ConnectionTicketStatus;
import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.User;
import com.peerlink.server.security.SecretTokens;
import com.peerlink.server.support.RepositoryTestSupport;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConnectionTicketRepositoryTests extends RepositoryTestSupport {

    @Autowired
    private ConnectionTicketRepository ticketRepository;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private Peer source;
    private Peer target;

    @BeforeEach
    void createPeers() {
        source = fixtures.onlinePeer(fixtures.user(), now);
        target = fixtures.onlinePeer(fixtures.user(), now);
    }

    @Test
    void storesTicketWithSnapshotOfTargetAddressAndFindsItByHash() {
        ConnectionTicket ticket = save("plt_example");
        entityManager.clear();

        ConnectionTicket loaded = ticketRepository.findByTicketHash(SecretTokens.sha256Hex("plt_example")).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(ticket.getId());
        assertThat(loaded.getStatus()).isEqualTo(ConnectionTicketStatus.ISSUED);
        assertThat(loaded.getTargetHost()).isEqualTo(target.getHostAddress());
        assertThat(loaded.getTargetPort()).isEqualTo(target.getListeningPort());
        assertThat(ticketRepository.findByIdAndSourcePeerId(ticket.getId(), source.getId())).isPresent();
        assertThat(ticketRepository.findByIdAndSourcePeerId(ticket.getId(), target.getId())).isEmpty();
    }

    @Test
    void redeemingClearsTheProof() {
        ConnectionTicket ticket = save("plt_redeem");

        assertThat(ticket.redeem(now)).hasSize(43);
        entityManager.flush();

        assertThat(ticket.getStatus()).isEqualTo(ConnectionTicketStatus.REDEEMED);
        assertThat(ticket.getRedeemedAt()).isEqualTo(now);
    }

    @Test
    void databaseRequiresRequesterToOwnSourcePeer() {
        ConnectionTicket ticket = save("plt_owner");
        User someoneElse = fixtures.user();

        assertSqlViolates("fk_connection_tickets_source_requester",
                "update connection_tickets set requested_by_id = ? where id = ?", someoneElse.getId(), ticket.getId());
    }

    @Test
    void databaseKeepsProofOnlyWhileTicketIsRedeemable() {
        ConnectionTicket ticket = save("plt_proof");

        assertSqlViolates("ck_connection_tickets_proof",
                "update connection_tickets set status = 'INVALIDATED' where id = ?", ticket.getId());
    }

    @Test
    void databaseRejectsUnknownOutcome() {
        ConnectionTicket ticket = save("plt_outcome");

        assertSqlViolates("ck_connection_tickets_outcome",
                "update connection_tickets set outcome = 'MAYBE', outcome_reported_at = now() where id = ?",
                ticket.getId());
    }

    @Test
    void databaseRejectsTicketToSelf() {
        ConnectionTicket ticket = save("plt_self");

        assertSqlViolates("ck_connection_tickets_distinct_peers",
                "update connection_tickets set target_peer_id = source_peer_id where id = ?", ticket.getId());
    }

    private ConnectionTicket save(String ticket) {
        return entityManager.persistAndFlush(new ConnectionTicket(source, target, SecretTokens.sha256Hex(ticket),
                SecretTokens.randomValue(), now, now.plusSeconds(60)));
    }
}
