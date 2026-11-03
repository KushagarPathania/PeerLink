package com.peerlink.agent.networking;

import com.peerlink.agent.protocol.FrameIO;
import com.peerlink.agent.protocol.Handshake;
import com.peerlink.agent.protocol.Handshake.Hello;
import com.peerlink.agent.protocol.Message;
import com.peerlink.agent.protocol.ProtocolChannel;
import com.peerlink.agent.protocol.ProtocolConstants;
import com.peerlink.agent.protocol.ProtocolException;
import com.peerlink.agent.protocol.RejectReason;
import com.peerlink.agent.protocol.TransferError;
import com.peerlink.agent.protocol.TransferMessages;
import com.peerlink.agent.security.CertificateFingerprints;
import com.peerlink.agent.security.PeerTls;
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
import java.util.concurrent.ScheduledFuture;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * Responder side of one incoming connection:
 * <pre>
 * TLS 1.3 handshake (client certificate required, ALPN peerlink/2)
 *   -> AWAIT_MAGIC -> AWAIT_HELLO -> (negotiate version, redeem ticket, match claimed identity
 *      and TLS client certificate) -> HELLO_ACK
 *   -> ESTABLISHED: PING -> PONG (repeatable), TRANSFER_REQUEST -> file transfer, BYE -> closed
 * any violation -> REJECT (before HELLO_ACK) or BYE (after) -> closed
 * </pre>
 * Everything up to HELLO_ACK must finish within {@code handshakeTimeout} (a watchdog closes the
 * socket otherwise); afterwards each message must arrive within {@code idleTimeout}, and no single
 * write may block for longer than {@code idleTimeout} either (a receiver that stops reading cannot
 * hold the connection). The presented TLS client certificate is checked by the coordination
 * server before the ticket is consumed, and again here. Tickets and proofs are never logged.
 */
public final class InboundConnectionHandler implements AutoCloseable {

    private static final Logger LOG = System.getLogger(InboundConnectionHandler.class.getName());
    private static final int OUTPUT_BUFFER = ProtocolConstants.MAX_DATA_CHUNK_BYTES + 16;

    private final UUID selfPeerId;
    private final TicketVerifier verifier;
    private final PeerTls tls;
    private final IncomingTransferHandler transfers;
    private final Duration handshakeTimeout;
    private final Duration idleTimeout;
    private final ConnectionWatchdog watchdog = new ConnectionWatchdog("peerlink-connection-watchdog");

    /** @param transfers serves file requests, or {@code null} if this agent only answers connection tests */
    public InboundConnectionHandler(UUID selfPeerId, TicketVerifier verifier, PeerTls tls,
                                    IncomingTransferHandler transfers, Duration handshakeTimeout,
                                    Duration idleTimeout) {
        this.selfPeerId = Objects.requireNonNull(selfPeerId);
        this.verifier = Objects.requireNonNull(verifier);
        this.tls = Objects.requireNonNull(tls);
        this.transfers = transfers;
        this.handshakeTimeout = handshakeTimeout;
        this.idleTimeout = idleTimeout;
    }

    /** Runs the protocol on an accepted socket. Never throws; the caller closes the socket. */
    public void handle(Socket accepted) {
        String remote = String.valueOf(accepted.getRemoteSocketAddress());
        // TLS and the PeerLink handshake together must finish in time, however slowly bytes arrive.
        ScheduledFuture<?> deadline = watchdog.schedule(() -> closeQuietly(accepted), handshakeTimeout);
        ConnectionWatchdog.Guard guard = watchdog.guard(accepted, idleTimeout);
        ProtocolChannel channel = null;
        boolean established = false;
        try {
            SSLSocket socket = tls.serverSide(accepted);
            socket.setSoTimeout((int) handshakeTimeout.toMillis());
            socket.startHandshake();
            PeerTls.requireApplicationProtocol(socket);
            DeadlineInputStream in = new DeadlineInputStream(socket, handshakeTimeout);
            channel = new ProtocolChannel(in, new BufferedOutputStream(guard.output(socket.getOutputStream()), OUTPUT_BUFFER));

            FrameIO.readMagic(in);
            Hello hello = Handshake.parseHello(channel.receive());
            int version = Handshake.negotiate(hello.versions()).orElseThrow(() -> new ProtocolException(
                    RejectReason.UNSUPPORTED_VERSION, "No common protocol version in " + hello.versions()));
            String presentedCertificate = PeerTls.peerFingerprint(socket);
            VerifiedTicket verified = verify(hello, presentedCertificate);
            // The server already compared the certificate before consuming the ticket; check its answer too.
            if (!CertificateFingerprints.matches(presentedCertificate, verified.initiatorCertificateSha256())) {
                throw new ProtocolException(RejectReason.UNAUTHORIZED,
                        "TLS client certificate does not belong to peer " + verified.sourcePeerId());
            }

            channel.send(Handshake.helloAck(version, selfPeerId, verified.responderProof()));
            established = true;
            deadline.cancel(false);
            LOG.log(Level.INFO, "Accepted connection from peer {0} (device \"{1}\" of {2}) at {3}",
                    verified.sourcePeerId(), printable(verified.sourceDeviceName()),
                    printable(verified.sourceOwnerUsername()), remote);
            serve(channel, in, guard, verified, remote);
        } catch (ProtocolException e) {
            LOG.log(Level.WARNING, "Rejected connection from {0}: {1} ({2})", remote, e.reason(), e.getMessage());
            sendQuietly(channel, established ? Handshake.bye(e.reason()) : Handshake.reject(e.reason()));
        } catch (SocketTimeoutException e) {
            LOG.log(Level.WARNING, "Connection from {0} timed out {1}", remote,
                    established ? "while idle" : "during the handshake");
        } catch (SSLException e) {
            LOG.log(Level.WARNING, "TLS with {0} failed: {1}", remote, e.getMessage());
        } catch (EOFException e) {
            LOG.log(Level.INFO, "{0} closed the connection{1}", remote, established ? "" : " during the handshake");
        } catch (IOException e) {
            LOG.log(Level.INFO, "Connection with {0} ended{1}: {2}", remote,
                    guard.writeTimedOut() ? " (the peer stopped reading)"
                            : deadline.isDone() && !established ? " (handshake deadline)" : "",
                    e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Unexpected error while serving " + remote, e);
            sendQuietly(channel, established ? Handshake.bye(RejectReason.INTERNAL_ERROR)
                    : Handshake.reject(RejectReason.INTERNAL_ERROR));
        } finally {
            deadline.cancel(false);
            guard.close();
        }
    }

    private VerifiedTicket verify(Hello hello, String presentedCertificate) throws ProtocolException {
        VerifiedTicket verified;
        try {
            verified = verifier.redeem(hello.ticket(), presentedCertificate);
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

    private void serve(ProtocolChannel channel, DeadlineInputStream in, ConnectionWatchdog.Guard guard,
                       VerifiedTicket verified, String remote)
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
                case TRANSFER_REQUEST -> {
                    if (transfers == null) {
                        channel.send(TransferMessages.reject(TransferMessages.requestedTransfer(message),
                                TransferError.PERMISSION_DENIED));
                        return;
                    }
                    // One transfer per connection.
                    transfers.handle(new TransferConnection(channel, in, guard, verified, remote), message);
                    return;
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

    @Override
    public void close() {
        watchdog.close();
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

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Already closed.
        }
    }

    /** Values that came from the server or the network are logged without control characters. */
    static String printable(String value) {
        return value == null ? "?" : value.replaceAll("\\p{Cntrl}", "?");
    }
}
