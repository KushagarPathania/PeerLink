package com.peerlink.agent.networking;

import com.peerlink.agent.security.ConnectionOutcome;
import com.peerlink.agent.security.IssuedTicket;
import com.peerlink.agent.security.TicketException;
import com.peerlink.agent.security.TicketException.Reason;
import com.peerlink.agent.security.TicketIssuer;
import com.peerlink.agent.security.TicketVerifier;
import com.peerlink.agent.security.VerifiedTicket;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test stand-in for the coordination server's ticket rules: single use, scoped to one target,
 * expirable and revocable. Peers "register" the address their listener is bound to.
 */
final class InMemoryTicketAuthority {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<UUID, InetSocketAddress> addresses = new ConcurrentHashMap<>();
    private final Map<String, Entry> tickets = new ConcurrentHashMap<>();
    final List<ConnectionOutcome> reportedOutcomes = new CopyOnWriteArrayList<>();
    volatile boolean serverDown;

    private static final class Entry {
        final UUID source;
        final UUID target;
        final String proof;
        boolean used;
        boolean expired;
        boolean revoked;

        Entry(UUID source, UUID target, String proof) {
            this.source = source;
            this.target = target;
            this.proof = proof;
        }
    }

    void register(UUID peerId, InetSocketAddress address) {
        addresses.put(peerId, address);
    }

    void expireAll() {
        tickets.values().forEach(entry -> entry.expired = true);
    }

    void revokeAll() {
        tickets.values().forEach(entry -> entry.revoked = true);
    }

    /** A ticket for any target, bypassing the address registry (to build misdirected tickets). */
    IssuedTicket issueTicket(UUID source, UUID target, InetSocketAddress address) {
        String ticket = "plt_" + random();
        String proof = random();
        tickets.put(ticket, new Entry(source, target, proof));
        return new IssuedTicket(UUID.randomUUID(), ticket, target, address.getAddress().getHostAddress(),
                address.getPort(), Instant.now().plusSeconds(60), proof);
    }

    TicketIssuer issuerFor(UUID sourcePeerId) {
        return new TicketIssuer() {
            @Override
            public IssuedTicket requestTicket(UUID targetPeerId) throws TicketException {
                if (serverDown) {
                    throw new TicketException(Reason.SERVER_UNAVAILABLE, "server down");
                }
                InetSocketAddress address = addresses.get(targetPeerId);
                if (address == null) {
                    throw new TicketException(Reason.TARGET_UNAVAILABLE, "not online");
                }
                return issueTicket(sourcePeerId, targetPeerId, address);
            }

            @Override
            public void reportOutcome(UUID ticketId, ConnectionOutcome outcome) {
                reportedOutcomes.add(outcome);
            }
        };
    }

    TicketVerifier verifierFor(UUID selfPeerId) {
        return ticket -> {
            if (serverDown) {
                throw new TicketException(Reason.SERVER_UNAVAILABLE, "server down");
            }
            synchronized (this) {
                Entry entry = tickets.get(ticket);
                if (entry == null || entry.used || entry.expired || entry.revoked) {
                    throw new TicketException(Reason.INVALID_TICKET, "invalid");
                }
                if (!entry.target.equals(selfPeerId)) {
                    entry.revoked = true; // burned, like the server does
                    throw new TicketException(Reason.INVALID_TICKET, "wrong target");
                }
                entry.used = true;
                return new VerifiedTicket(UUID.randomUUID(), entry.source, "device", "owner", entry.proof);
            }
        };
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
