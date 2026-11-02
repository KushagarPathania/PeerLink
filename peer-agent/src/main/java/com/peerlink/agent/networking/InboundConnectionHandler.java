package com.peerlink.agent.networking;

import com.peerlink.agent.protocol.FrameIO;
import com.peerlink.agent.protocol.Handshake;
import com.peerlink.agent.protocol.Handshake.Hello;
import com.peerlink.agent.protocol.Message;
import com.peerlink.agent.protocol.ProtocolChannel;
import com.peerlink.agent.protocol.ProtocolConstants;
import com.peerlink.agent.protocol.ProtocolException;
import com.peerlink.agent.protocol.RejectReason;
import com.peerlink.agent.security.TicketException;
import com.peerlink.agent.security.TicketVerifier;
import com.peerlink.agent.security.VerifiedTicket;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Responder side of one incoming connection:
 * <pre>
 * AWAIT_MAGIC -> AWAIT_HELLO -> (negotiate version, redeem ticket, match identity) -> HELLO_ACK
 *   -> ESTABLISHED: PING -> PONG (repeatable), BYE -> closed
 * any violation -> REJECT (before HELLO_ACK) or BYE (after) -> closed
 * </pre>
 * The whole handshake must finish within {@code handshakeTimeout}; afterwards each message must
 * arrive within {@code idleTimeout}. Tickets and proofs are never logged.
 */
public final class InboundConnectionHandler {

    private static final Logger LOG = System.getLogger(InboundConnectionHandler.class.getName());

    private final UUID selfPeerId;
    private final TicketVerifier verifier;
    private final Duration handshakeTimeout;
    private final Duration idleTimeout;

    public InboundConnectionHandler(UUID selfPeerId, TicketVerifier verifier, Duration handshakeTimeout,
                                    Duration idleTimeout) {
        this.selfPeerId = Objects.requireNonNull(selfPeerId);
        this.verifier = Objects.requireNonNull(verifier);
        this.handshakeTimeout = handshakeTimeout;
        this.idleTimeout = idleTimeout;
    }

    /** Runs the protocol on an accepted socket. Never throws; the caller closes the socket. */
    public void handle(Socket socket) {
        String remote = String.valueOf(socket.getRemoteSocketAddress());
        ProtocolChannel channel = null;
        boolean established = false;
        try {
            DeadlineInputStream in = new DeadlineInputStream(socket, handshakeTimeout);
            channel = new ProtocolChannel(in, new BufferedOutputStream(socket.getOutputStream()));

            FrameIO.readMagic(in);
            Hello hello = Handshake.parseHello(channel.receive());
            int version = Handshake.negotiate(hello.versions()).orElseThrow(() -> new ProtocolException(
                    RejectReason.UNSUPPORTED_VERSION, "No common protocol version in " + hello.versions()));
            VerifiedTicket verified = verify(hello);

            channel.send(Handshake.helloAck(version, selfPeerId, verified.responderProof()));
            established = true;
            LOG.log(Level.INFO, "Accepted connection from peer {0} (device \"{1}\" of {2}) at {3}",
                    verified.sourcePeerId(), printable(verified.sourceDeviceName()),
                    printable(verified.sourceOwnerUsername()), remote);
            serve(channel, in, remote);
        } catch (ProtocolException e) {
            LOG.log(Level.WARNING, "Rejected connection from {0}: {1} ({2})", remote, e.reason(), e.getMessage());
            sendQuietly(channel, established ? Handshake.bye(e.reason()) : Handshake.reject(e.reason()));
        } catch (SocketTimeoutException e) {
            LOG.log(Level.WARNING, "Connection from {0} timed out {1}", remote,
                    established ? "while idle" : "during the handshake");
        } catch (EOFException e) {
            LOG.log(Level.INFO, "{0} closed the connection{1}", remote, established ? "" : " during the handshake");
        } catch (IOException e) {
            LOG.log(Level.INFO, "Connection with {0} ended: {1}", remote, e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Unexpected error while serving " + remote, e);
            sendQuietly(channel, established ? Handshake.bye(RejectReason.INTERNAL_ERROR)
                    : Handshake.reject(RejectReason.INTERNAL_ERROR));
        }
    }

    private VerifiedTicket verify(Hello hello) throws ProtocolException {
        VerifiedTicket verified;
        try {
            verified = verifier.redeem(hello.ticket());
        } catch (TicketException e) {
            if (e.reason() == TicketException.Reason.INVALID_TICKET) {
                throw new ProtocolException(RejectReason.UNAUTHORIZED, "Ticket rejected by the coordination server");
            }
            // Cannot verify (server down, or our own credential rejected): fail closed.
            throw new ProtocolException(RejectReason.UNAVAILABLE, "Cannot verify ticket: " + e.getMessage());
        }
        if (!verified.sourcePeerId().equals(hello.peerId())) {
            throw new ProtocolException(RejectReason.UNAUTHORIZED,
                    "Claimed peer id " + hello.peerId() + " does not match the ticket's source");
        }
        return verified;
    }

    private void serve(ProtocolChannel channel, DeadlineInputStream in, String remote)
            throws IOException, ProtocolException {
        int pings = 0;
        while (true) {
            in.extend(idleTimeout);
            Message message = channel.receive();
            switch (message.type()) {
                case PING -> {
                    if (++pings > ProtocolConstants.MAX_PINGS_PER_CONNECTION) {
                        channel.send(Handshake.bye(RejectReason.TOO_MANY_MESSAGES));
                        LOG.log(Level.INFO, "Closed connection with {0} after {1} connection tests", remote, pings - 1);
                        return;
                    }
                    channel.send(Handshake.pong(Handshake.nonce(message)));
                }
                case BYE -> {
                    LOG.log(Level.INFO, "Peer at {0} closed the connection", remote);
                    return;
                }
                default -> throw new ProtocolException(RejectReason.UNEXPECTED_MESSAGE,
                        "Unexpected " + message.type() + " after the handshake");
            }
        }
    }

    private static void sendQuietly(ProtocolChannel channel, Message message) {
        if (channel == null) {
            return;
        }
        try {
            channel.send(message);
        } catch (IOException e) {
            // The peer is already gone; nothing more to tell it.
        }
    }

    /** Values that came from the server or the network are logged without control characters. */
    static String printable(String value) {
        return value == null ? "?" : value.replaceAll("\\p{Cntrl}", "?");
    }
}
