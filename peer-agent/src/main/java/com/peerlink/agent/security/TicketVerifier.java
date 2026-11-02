package com.peerlink.agent.security;

/**
 * Responder side: redeems a ticket a connecting peer presented. A ticket can be redeemed only
 * once, only by the peer it was issued for, and only before it expires.
 */
public interface TicketVerifier {

    VerifiedTicket redeem(String ticket) throws TicketException;
}
