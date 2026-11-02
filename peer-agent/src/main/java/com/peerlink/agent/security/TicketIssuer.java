package com.peerlink.agent.security;

import java.util.UUID;

/** Initiator side: obtains connection tickets for this agent's peer and reports how attempts ended. */
public interface TicketIssuer {

    IssuedTicket requestTicket(UUID targetPeerId) throws TicketException;

    /** Best effort; failures are logged, never thrown, so they cannot affect the connection result. */
    void reportOutcome(UUID ticketId, ConnectionOutcome outcome);
}
