package com.peerlink.agent.networking;

import com.peerlink.agent.protocol.ProtocolChannel;
import com.peerlink.agent.security.VerifiedTicket;

/**
 * An established, authenticated incoming connection that asked for a file transfer.
 *
 * @param guard  bounds every write and lets another thread abort the connection (closing it unblocks
 *               a thread stuck reading or writing)
 * @param ticket what the coordination server said about the connecting peer when the ticket was redeemed
 * @param remote the remote socket address, for logs
 */
public record TransferConnection(ProtocolChannel channel, DeadlineInputStream input, ConnectionWatchdog.Guard guard,
                                 VerifiedTicket ticket, String remote) {
}
