package com.peerlink.agent.security;

/**
 * Responder side: redeems a ticket a connecting peer presented. A ticket can be redeemed only
 * once, only by the peer it was issued for, only before it expires, and only over a TLS
 * connection whose client certificate is the one the ticket was issued for; a ticket presented
 * with another certificate is refused without being consumed.
 */
public interface TicketVerifier {

    /** @param presentedCertificateSha256 fingerprint of the TLS client certificate the ticket arrived over */
    VerifiedTicket redeem(String ticket, String presentedCertificateSha256) throws TicketException;
}
