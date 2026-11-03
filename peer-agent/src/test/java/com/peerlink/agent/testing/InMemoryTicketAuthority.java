package com.peerlink.agent.testing;

import com.peerlink.agent.security.ConnectionOutcome;
import com.peerlink.agent.security.IssuedTicket;
import com.peerlink.agent.security.TicketException;
import com.peerlink.agent.security.TicketException.Reason;
import com.peerlink.agent.security.TicketIssuer;
import com.peerlink.agent.security.TicketVerifier;
import com.peerlink.agent.security.TransferGrant;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test stand-in for the coordination server's ticket rules: single use, scoped to one source and
 * one target, expirable and revocable, carrying both peers' TLS fingerprints and, for transfers,
 * the authorized transfer. Peers "register" their listener address and certificate fingerprint.
 */
public final class InMemoryTicketAuthority {

    private static final SecureRandom RANDOM = new SecureRandom();

    private record Registration(InetSocketAddress address, String certificate) {
    }

    private static final class Entry {
        final UUID source;
        final String sourceCertificate;
        final UUID target;
        final String proof;
        final TransferGrant grant;
        boolean used;
        boolean expired;
        boolean revoked;

        Entry(UUID source, String sourceCertificate, UUID target, String proof, TransferGrant grant) {
            this.source = source;
            this.sourceCertificate = sourceCertificate;
            this.target = target;
            this.proof = proof;
            this.grant = grant;
        }
    }

    private final Map<UUID, Registration> peers = new ConcurrentHashMap<>();
    private final Map<UUID, TransferGrant> grants = new ConcurrentHashMap<>();
    private final Map<String, Entry> tickets = new ConcurrentHashMap<>();
    public final List<ConnectionOutcome> reportedOutcomes = new CopyOnWriteArrayList<>();
    public volatile boolean serverDown;
    /** Simulates a server that does not compare the presented certificate (to test the agent's own check). */
    public volatile boolean presentedWithAnotherCertificateAllowed;
    public final AtomicInteger refusedForCertificate = new AtomicInteger();

    public void register(UUID peerId, InetSocketAddress address, String certificateFingerprint) {
        peers.put(peerId, new Registration(address, certificateFingerprint));
    }

    /** Authorizes a transfer, as the server does when a user requests one. */
    public void grant(TransferGrant grant) {
        grants.put(grant.transferId(), grant);
    }

    public void expireAll() {
        tickets.values().forEach(entry -> entry.expired = true);
    }

    public void revokeAll() {
        tickets.values().forEach(entry -> entry.revoked = true);
    }

    /** A ticket with arbitrary contents (to build misdirected or impostor scenarios). */
    public IssuedTicket issueTicket(UUID source, String sourceCertificate, UUID target, InetSocketAddress address,
                                    String targetCertificate, TransferGrant grant) {
        String ticket = "plt_" + random();
        String proof = random();
        tickets.put(ticket, new Entry(source, sourceCertificate, target, proof, grant));
        return new IssuedTicket(UUID.randomUUID(), ticket, target, address.getAddress().getHostAddress(),
                address.getPort(), Instant.now().plusSeconds(60), proof, targetCertificate,
                grant == null ? null : grant.transferId());
    }

    public TicketIssuer issuerFor(UUID sourcePeerId, String sourceCertificate) {
        return new TicketIssuer() {
            @Override
            public IssuedTicket requestTicket(UUID targetPeerId, UUID transferId) throws TicketException {
                if (serverDown) {
                    throw new TicketException(Reason.SERVER_UNAVAILABLE, "server down");
                }
                Registration target = peers.get(targetPeerId);
                if (target == null) {
                    throw new TicketException(Reason.TARGET_UNAVAILABLE, "not online");
                }
                TransferGrant grant = null;
                if (transferId != null) {
                    grant = grants.get(transferId);
                    if (grant == null) {
                        throw new TicketException(Reason.TARGET_UNAVAILABLE, "transfer not authorized");
                    }
                }
                return issueTicket(sourcePeerId, sourceCertificate, targetPeerId, target.address(), target.certificate(),
                        grant);
            }

            @Override
            public void reportOutcome(UUID ticketId, ConnectionOutcome outcome) {
                reportedOutcomes.add(outcome);
            }
        };
    }

    public TicketVerifier verifierFor(UUID selfPeerId) {
        return (ticket, presentedCertificate) -> {
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
                if (!presentedWithAnotherCertificateAllowed && !entry.sourceCertificate.equals(presentedCertificate)) {
                    refusedForCertificate.incrementAndGet(); // refused, but left unused, like the server does
                    throw new TicketException(Reason.INVALID_TICKET, "presented with another certificate");
                }
                entry.used = true;
                return new VerifiedTicket(UUID.randomUUID(), entry.source, "device", "owner", entry.proof,
                        entry.sourceCertificate, entry.grant);
            }
        };
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
