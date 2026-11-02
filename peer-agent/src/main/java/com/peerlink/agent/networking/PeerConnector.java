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
import com.peerlink.agent.security.ConnectionOutcome;
import com.peerlink.agent.security.IssuedTicket;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Initiator side: obtains a ticket, connects directly to the target peer, completes the handshake
 * (including checking the target's proof of identity), runs one PING/PONG connection test and
 * closes. One attempt only; it never retries. No file data is sent.
 */
public final class PeerConnector {

    /** @param handshakeTimeout total time allowed for the handshake and the connection test */
    public record Settings(Duration connectTimeout, Duration handshakeTimeout) {
        public static Settings defaults() {
            return new Settings(Duration.ofSeconds(5), Duration.ofSeconds(15));
        }
    }

    private static final SecureRandom NONCES = new SecureRandom();

    private final UUID selfPeerId;
    private final TicketIssuer issuer;
    private final Settings settings;

    public PeerConnector(UUID selfPeerId, TicketIssuer issuer, Settings settings) {
        this.selfPeerId = Objects.requireNonNull(selfPeerId);
        this.issuer = Objects.requireNonNull(issuer);
        this.settings = Objects.requireNonNull(settings);
    }

    /** Never throws; every failure is described in the result and reported to the server when a ticket was used. */
    public ConnectionTestResult testConnection(UUID targetPeerId) {
        IssuedTicket ticket;
        try {
            ticket = issuer.requestTicket(targetPeerId);
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

    /** Performs the TCP part with an already issued ticket. */
    ConnectionTestResult connect(IssuedTicket ticket) {
        String target = ticket.targetHost() + ":" + ticket.targetPort();
        boolean connected = false;
        try (Socket socket = new Socket()) {
            InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(ticket.targetHost()),
                    ticket.targetPort());
            socket.connect(address, (int) settings.connectTimeout().toMillis());
            connected = true;
            socket.setTcpNoDelay(true);
            DeadlineInputStream in = new DeadlineInputStream(socket, settings.handshakeTimeout());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            ProtocolChannel channel = new ProtocolChannel(in, out);

            FrameIO.writeMagic(out);
            channel.send(Handshake.hello(selfPeerId, ticket.ticket()));
            Message reply = channel.receive();
            if (reply.type() == MessageType.REJECT) {
                RejectReason reason = RejectReason.parse(reply.optionalField("reason"));
                return result(ticket, Outcome.REJECTED_BY_PEER, "Peer at " + target + " rejected the connection: " + reason);
            }
            HelloAck ack = Handshake.parseHelloAck(reply);
            if (!ProtocolConstants.SUPPORTED_VERSIONS.contains(ack.version())) {
                return result(ticket, Outcome.PROTOCOL_ERROR, "Peer chose unsupported protocol version " + ack.version());
            }
            if (!ack.peerId().equals(ticket.targetPeerId()) || !proofMatches(ack.proof(), ticket.responderProof())) {
                channel.send(Handshake.bye(RejectReason.UNAUTHORIZED));
                return result(ticket, Outcome.PEER_IDENTITY_MISMATCH,
                        "The service at " + target + " could not prove it is peer " + ticket.targetPeerId());
            }

            String nonce = newNonce();
            long start = System.nanoTime();
            channel.send(Handshake.ping(nonce));
            Message pong = channel.receive();
            if (pong.type() != MessageType.PONG || !nonce.equals(Handshake.nonce(pong))) {
                return result(ticket, Outcome.PROTOCOL_ERROR, "Invalid reply to the connection test");
            }
            Duration roundTrip = Duration.ofNanos(System.nanoTime() - start);
            channel.send(Handshake.bye(null));
            return new ConnectionTestResult(Outcome.SUCCEEDED, "Connected to peer " + ticket.targetPeerId() + " at "
                    + target + " using protocol v" + ack.version(), roundTrip, ticket.ticketId());
        } catch (SocketTimeoutException e) {
            return result(ticket, Outcome.TIMEOUT, connected
                    ? "No reply from " + target + " within " + settings.handshakeTimeout().toSeconds() + " s"
                    : "Connecting to " + target + " timed out after " + settings.connectTimeout().toSeconds() + " s");
        } catch (ConnectException | NoRouteToHostException e) {
            return result(ticket, Outcome.CONNECT_FAILED, "Could not connect to " + target + " (" + e.getMessage()
                    + "). Is the peer's agent listening on that address and port, and does its firewall allow it?");
        } catch (EOFException e) {
            return result(ticket, Outcome.PROTOCOL_ERROR, "Peer at " + target + " closed the connection unexpectedly");
        } catch (ProtocolException e) {
            return result(ticket, Outcome.PROTOCOL_ERROR, "Protocol error from " + target + ": " + e.getMessage());
        } catch (IOException e) {
            return result(ticket, connected ? Outcome.PROTOCOL_ERROR : Outcome.CONNECT_FAILED,
                    (connected ? "Connection to " : "Could not connect to ") + target + " ("
                            + e.getClass().getSimpleName() + ")");
        }
    }

    private static ConnectionTestResult result(IssuedTicket ticket, Outcome outcome, String detail) {
        return new ConnectionTestResult(outcome, detail, null, ticket.ticketId());
    }

    /** Constant-time comparison, so response timing reveals nothing about the expected proof. */
    private static boolean proofMatches(String received, String expected) {
        return MessageDigest.isEqual(received.getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII));
    }

    private static String newNonce() {
        byte[] bytes = new byte[16];
        NONCES.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
