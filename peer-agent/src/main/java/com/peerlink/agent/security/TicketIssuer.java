package com.peerlink.agent.security;

import java.util.UUID;

/** Initiator side: obtains connection tickets for this agent's peer and reports how attempts ended. */
public interface TicketIssuer {

    /** @param transferId the transfer to fetch over this connection, or {@code null} for a connection test */
    IssuedTicket requestTicket(UUID targetPeerId, UUID transferId) throws TicketException;

    /** Best effort; failures are logged, never thrown, so they cannot affect the connection result. */
    void reportOutcome(UUID ticketId, ConnectionOutcome outcome);
}
