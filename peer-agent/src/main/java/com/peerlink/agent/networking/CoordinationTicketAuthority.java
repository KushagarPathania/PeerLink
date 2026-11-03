package com.peerlink.agent.networking;

import com.peerlink.agent.config.AgentCredential;
import com.peerlink.agent.security.ConnectionOutcome;
import com.peerlink.agent.security.IssuedTicket;
import com.peerlink.agent.security.TicketException;
import com.peerlink.agent.security.TicketIssuer;
import com.peerlink.agent.security.TicketVerifier;
import com.peerlink.agent.security.VerifiedTicket;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.UUID;

/**
 * Connection tickets backed by the coordination server, acting for this agent's own peer. The
 * long-lived agent credential stays here and in the HTTPS/HTTP client; it never reaches other peers.
 */
public final class CoordinationTicketAuthority implements TicketIssuer, TicketVerifier {

    private static final Logger LOG = System.getLogger(CoordinationTicketAuthority.class.getName());

    private final CoordinationServerClient client;
    private final UUID selfPeerId;
    private final AgentCredential credential;
    private final String certificateFingerprint;

    /** @param certificateFingerprint fingerprint of the TLS certificate this agent presents when connecting */
    public CoordinationTicketAuthority(CoordinationServerClient client, UUID selfPeerId, AgentCredential credential,
                                       String certificateFingerprint) {
        this.client = Objects.requireNonNull(client);
        this.selfPeerId = Objects.requireNonNull(selfPeerId);
        this.credential = Objects.requireNonNull(credential);
        this.certificateFingerprint = Objects.requireNonNull(certificateFingerprint);
    }

    @Override
    public IssuedTicket requestTicket(UUID targetPeerId, UUID transferId) throws TicketException {
        return client.requestConnectionTicket(selfPeerId, credential, targetPeerId, transferId, certificateFingerprint);
    }

    @Override
    public void reportOutcome(UUID ticketId, ConnectionOutcome outcome) {
        if (!client.reportConnectionOutcome(selfPeerId, credential, ticketId, outcome)) {
            LOG.log(Level.WARNING, "Could not report the outcome of connection {0} to the server", ticketId);
        }
    }

    @Override
    public VerifiedTicket redeem(String ticket, String presentedCertificateSha256) throws TicketException {
        return client.redeemConnectionTicket(selfPeerId, credential, ticket, presentedCertificateSha256);
    }
}
