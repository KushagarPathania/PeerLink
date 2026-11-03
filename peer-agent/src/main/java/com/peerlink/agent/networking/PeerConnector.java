package com.peerlink.agent.networking;

import com.peerlink.agent.networking.ConnectionTestResult.Outcome;
import com.peerlink.agent.protocol.FrameIO;
import com.peerlink.agent.protocol.Handshake;
import com.peerlink.agent.protocol.Handshake.HelloAck;
import com.peerlink.agent.protocol.Message;
import com.peerlink.agent.protocol.MessageType;
import com.peerlink.agent.protocol.ProtocolChannel;
import com.peerlink.agent.protocol.ProtocolConstants;
import com.peerlink.agent.protocol.ProtocolException;
import com.peerlink.agent.protocol.RejectReason;
import com.peerlink.agent.security.CertificateFingerprints;
import com.peerlink.agent.security.ConnectionOutcome;
import com.peerlink.agent.security.IssuedTicket;
import com.peerlink.agent.security.PeerTls;
import com.peerlink.agent.security.TicketException;
import com.peerlink.agent.security.TicketIssuer;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;

/**
 * Initiator side: connects directly to a peer with an issued ticket, completes TLS (pinning the
 * target's certificate) and the PeerLink handshake (checking the target's proof), and hands back
 * the established connection. One attempt only; it never retries.
 */
public final class PeerConnector {

    /**
     * @param handshakeTimeout total time allowed for TLS, the handshake and a connection test; also
     *                         the longest any single write may block on an established connection
     */
    public record Settings(Duration connectTimeout, Duration handshakeTimeout) {
        public static Settings defaults() {
            return new Settings(Duration.ofSeconds(5), Duration.ofSeconds(15));
        }
    }

    /** Why a connection could not be established; {@code outcome} is never SUCCEEDED. */
    public static final class ConnectionFailure extends Exception {
        private final Outcome outcome;

        ConnectionFailure(Outcome outcome, String message) {
            super(message);
            this.outcome = outcome;
        }

        public Outcome outcome() {
            return outcome;
        }
    }

    /** An authenticated, encrypted connection to the ticket's target peer. */
    public static final class Established implements AutoCloseable {
        private final SSLSocket socket;
        private final ConnectionWatchdog.Guard guard;
        private final DeadlineInputStream input;
        private final ProtocolChannel channel;
        private final int version;

        private Established(SSLSocket socket, ConnectionWatchdog.Guard guard, DeadlineInputStream input,
                            ProtocolChannel channel, int version) {
            this.socket = socket;
            this.guard = guard;
            this.input = input;
            this.channel = channel;
            this.version = version;
        }

        public ProtocolChannel channel() {
            return channel;
        }

        /** Each subsequent read must complete within {@code timeout}. */
        public void extendDeadline(Duration timeout) {
            input.extend(timeout);
        }

        public int version() {
            return version;
        }

        /** Whether the connection was closed because a write did not finish in time. */
        public boolean writeTimedOut() {
            return guard.writeTimedOut();
        }

        /**
         * Closes the connection from another thread, unblocking the thread using it (shutdown). Safe
         * while that thread is inside a read or write, unlike {@link #close()}.
         */
        public void abort() {
            guard.abort();
        }

        /** Closes the connection; called by the thread that uses it. */
        @Override
        public void close() {
            guard.close();
            try {
                socket.close();
            } catch (IOException e) {
                // Already closed.
            }
        }
    }

    private static final SecureRandom NONCES = new SecureRandom();
    private static final int OUTPUT_BUFFER = ProtocolConstants.MAX_DATA_CHUNK_BYTES + 16;

    private final UUID selfPeerId;
    private final TicketIssuer issuer;
    private final PeerTls tls;
    private final Settings settings;
    private final ConnectionWatchdog watchdog = new ConnectionWatchdog("peerlink-outgoing-watchdog");

    public PeerConnector(UUID selfPeerId, TicketIssuer issuer, PeerTls tls, Settings settings) {
        this.selfPeerId = Objects.requireNonNull(selfPeerId);
        this.issuer = Objects.requireNonNull(issuer);
        this.tls = Objects.requireNonNull(tls);
        this.settings = Objects.requireNonNull(settings);
    }

    public TicketIssuer issuer() {
        return issuer;
    }

    /** Never throws; every failure is described in the result and reported to the server when a ticket was used. */
    public ConnectionTestResult testConnection(UUID targetPeerId) {
        IssuedTicket ticket;
        try {
            ticket = issuer.requestTicket(targetPeerId, null);
        } catch (TicketException e) {
            Outcome outcome = switch (e.reason()) {
                case TARGET_UNAVAILABLE -> Outcome.TARGET_UNAVAILABLE;
                case CREDENTIAL_REJECTED -> Outcome.CREDENTIAL_REJECTED;
                case SERVER_UNAVAILABLE, INVALID_RESPONSE, INVALID_TICKET -> Outcome.SERVER_UNAVAILABLE;
            };
            return new ConnectionTestResult(outcome, e.getMessage(), null, null);
        }
        ConnectionTestResult result = connect(ticket);
        ConnectionOutcome reported = result.outcome().reported();
        if (reported != null) {
            issuer.reportOutcome(ticket.ticketId(), reported);
        }
        return result;
    }

    /** Connection test with an already issued ticket: establish, PING/PONG, BYE. */
    ConnectionTestResult connect(IssuedTicket ticket) {
        String target = ticket.targetHost() + ":" + ticket.targetPort();
        try (Established connection = establish(ticket)) {
            String nonce = newNonce();
            long start = System.nanoTime();
            connection.channel().send(Handshake.ping(nonce));
            Message pong = connection.channel().receive();
            if (pong.type() != MessageType.PONG || !nonce.equals(Handshake.nonce(pong))) {
                return result(ticket, Outcome.PROTOCOL_ERROR, "Invalid reply to the connection test");
            }
            Duration roundTrip = Duration.ofNanos(System.nanoTime() - start);
            connection.channel().send(Handshake.bye(null));
            return new ConnectionTestResult(Outcome.SUCCEEDED, "Connected to peer " + ticket.targetPeerId() + " at "
                    + target + " using protocol v" + connection.version() + " over TLS 1.3", roundTrip,
                    ticket.ticketId());
        } catch (ConnectionFailure failure) {
            return result(ticket, failure.outcome(), failure.getMessage());
        } catch (SocketTimeoutException e) {
            return result(ticket, Outcome.TIMEOUT, "No reply from " + target + " within "
                    + settings.handshakeTimeout().toSeconds() + " s");
        } catch (ProtocolException | IOException e) {
            return result(ticket, Outcome.PROTOCOL_ERROR, "Connection test with " + target + " failed: " + e.getMessage());
        }
    }

    /**
     * Opens TCP, completes TLS 1.3 pinned to the target's certificate fingerprint, and the PeerLink
     * handshake including the target's proof. The caller owns (and must close) the result.
     */
    public Established establish(IssuedTicket ticket) throws ConnectionFailure {
        String target = ticket.targetHost() + ":" + ticket.targetPort();
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(InetAddress.getByName(ticket.targetHost()), ticket.targetPort()),
                    (int) settings.connectTimeout().toMillis());
        } catch (SocketTimeoutException e) {
            closeQuietly(raw);
            throw new ConnectionFailure(Outcome.TIMEOUT, "Connecting to " + target + " timed out after "
                    + settings.connectTimeout().toSeconds() + " s");
        } catch (ConnectException | NoRouteToHostException e) {
            closeQuietly(raw);
            throw new ConnectionFailure(Outcome.CONNECT_FAILED, "Could not connect to " + target + " (" + e.getMessage()
                    + "). Is the peer's agent listening on that address and port, and does its firewall allow it?");
        } catch (IOException e) {
            closeQuietly(raw);
            throw new ConnectionFailure(Outcome.CONNECT_FAILED,
                    "Could not connect to " + target + " (" + e.getClass().getSimpleName() + ")");
        }

        SSLSocket socket = null;
        ConnectionWatchdog.Guard guard = watchdog.guard(raw, settings.handshakeTimeout());
        try {
            raw.setTcpNoDelay(true);
            socket = tls.clientSide(raw, ticket.targetCertificateSha256());
            socket.setSoTimeout((int) settings.handshakeTimeout().toMillis());
            socket.startHandshake();
            PeerTls.requireApplicationProtocol(socket);
            DeadlineInputStream in = new DeadlineInputStream(socket, settings.handshakeTimeout());
            OutputStream out = guard.output(socket.getOutputStream());
            ProtocolChannel channel = new ProtocolChannel(in, new BufferedOutputStream(out, OUTPUT_BUFFER));

            FrameIO.writeMagic(out);
            channel.send(Handshake.hello(selfPeerId, ticket.ticket()));
            Message reply = channel.receive();
            if (reply.type() == MessageType.REJECT) {
                RejectReason reason = RejectReason.parse(reply.optionalField("reason"));
                throw new ConnectionFailure(Outcome.REJECTED_BY_PEER,
                        "Peer at " + target + " rejected the connection: " + reason);
            }
            HelloAck ack = Handshake.parseHelloAck(reply);
            if (!ProtocolConstants.SUPPORTED_VERSIONS.contains(ack.version())) {
                throw new ConnectionFailure(Outcome.PROTOCOL_ERROR, "Peer chose unsupported protocol version " + ack.version());
            }
            if (!ack.peerId().equals(ticket.targetPeerId())
                    || !CertificateFingerprints.matches(ack.proof(), ticket.responderProof())) {
                channel.send(Handshake.bye(RejectReason.UNAUTHORIZED));
                throw new ConnectionFailure(Outcome.PEER_IDENTITY_MISMATCH,
                        "The service at " + target + " could not prove it is peer " + ticket.targetPeerId());
            }
            return new Established(socket, guard, in, channel, ack.version());
        } catch (ConnectionFailure failure) {
            guard.close();
            closeQuietly(socket != null ? socket : raw);
            throw failure;
        } catch (SSLHandshakeException e) {
            guard.close();
            closeQuietly(socket != null ? socket : raw);
            if (PeerTls.isFingerprintMismatch(e)) {
                throw new ConnectionFailure(Outcome.PEER_IDENTITY_MISMATCH,
                        "The TLS certificate at " + target + " is not the one registered for peer " + ticket.targetPeerId());
            }
            throw new ConnectionFailure(Outcome.PROTOCOL_ERROR, "TLS handshake with " + target + " failed: " + e.getMessage());
        } catch (SocketTimeoutException e) {
            guard.close();
            closeQuietly(socket != null ? socket : raw);
            throw new ConnectionFailure(Outcome.TIMEOUT, "No reply from " + target + " within "
                    + settings.handshakeTimeout().toSeconds() + " s");
        } catch (EOFException e) {
            guard.close();
            closeQuietly(socket != null ? socket : raw);
            throw new ConnectionFailure(Outcome.PROTOCOL_ERROR, "Peer at " + target + " closed the connection unexpectedly");
        } catch (ProtocolException e) {
            guard.close();
            closeQuietly(socket != null ? socket : raw);
            throw new ConnectionFailure(Outcome.PROTOCOL_ERROR, "Protocol error from " + target + ": " + e.getMessage());
        } catch (SSLException e) {
            guard.close();
            closeQuietly(socket != null ? socket : raw);
            throw new ConnectionFailure(Outcome.PROTOCOL_ERROR, "TLS error with " + target + ": " + e.getMessage());
        } catch (IOException e) {
            guard.close();
            closeQuietly(socket != null ? socket : raw);
            throw new ConnectionFailure(Outcome.PROTOCOL_ERROR,
                    "Connection to " + target + " failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static ConnectionTestResult result(IssuedTicket ticket, Outcome outcome, String detail) {
        return new ConnectionTestResult(outcome, detail, null, ticket.ticketId());
    }

    private static String newNonce() {
        byte[] bytes = new byte[16];
        NONCES.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Already closed.
        }
    }
}
