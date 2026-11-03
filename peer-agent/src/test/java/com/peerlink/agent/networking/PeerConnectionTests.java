package com.peerlink.agent.networking;

import static org.assertj.core.api.Assertions.assertThat;

import com.peerlink.agent.networking.ConnectionTestResult.Outcome;
import com.peerlink.agent.protocol.FrameIO;
import com.peerlink.agent.protocol.Handshake;
import com.peerlink.agent.protocol.Message;
import com.peerlink.agent.protocol.MessageCodec;
import com.peerlink.agent.protocol.MessageType;
import com.peerlink.agent.protocol.RejectReason;
import com.peerlink.agent.security.ConnectionOutcome;
import com.peerlink.agent.security.IssuedTicket;
import com.peerlink.agent.security.PeerTls;
import com.peerlink.agent.security.TicketVerifier;
import com.peerlink.agent.security.TlsIdentity;
import com.peerlink.agent.testing.InMemoryTicketAuthority;
import com.peerlink.agent.testing.RawPeer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real TLS sockets over loopback: agent A connects to agent B's listener. */
class PeerConnectionTests {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final Duration SHORT = Duration.ofMillis(400);

    private final UUID peerA = UUID.randomUUID();
    private final UUID peerB = UUID.randomUUID();
    private final PeerTls tlsA = new PeerTls(TlsIdentity.generate("A"));
    private final PeerTls tlsB = new PeerTls(TlsIdentity.generate("B"));
    private final InMemoryTicketAuthority authority = new InMemoryTicketAuthority();
    private final List<AutoCloseable> resources = new ArrayList<>();
    private PeerListener listenerB;

    @BeforeEach
    void startPeerB() throws Exception {
        listenerB = startListener(peerB, tlsB, authority.verifierFor(peerB), 4, Duration.ofSeconds(5));
    }

    @AfterEach
    void closeAll() throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
    }

    // --- Successful connections --------------------------------------------------------

    @Test
    void twoAgentsCompleteHandshakeAndConnectionTestOverTls() {
        ConnectionTestResult result = connectorA().testConnection(peerB);

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(result.roundTrip()).isNotNull().isPositive();
        assertThat(result.detail()).contains(peerB.toString()).contains("TLS 1.3");
        assertThat(authority.reportedOutcomes).containsExactly(ConnectionOutcome.SUCCEEDED);
    }

    @Test
    void servesSeveralSimultaneousConnections() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Callable<ConnectionTestResult>> attempts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID source = UUID.randomUUID();
            PeerTls tls = new PeerTls(TlsIdentity.generate("source-" + i));
            attempts.add(() -> new PeerConnector(source, authority.issuerFor(source, tls.identity().fingerprint()), tls,
                    PeerConnector.Settings.defaults()).testConnection(peerB));
        }
        List<ConnectionTestResult> results = new ArrayList<>();
        for (Future<ConnectionTestResult> future : pool.invokeAll(attempts)) {
            results.add(future.get());
        }
        pool.shutdown();

        assertThat(results).extracting(ConnectionTestResult::outcome).containsOnly(Outcome.SUCCEEDED);
    }

    // --- Authorization and identity ------------------------------------------------------

    @Test
    void invalidOrRevokedTicketIsRejected() {
        IssuedTicket ticket = ticketFromAToB();
        authority.revokeAll();

        ConnectionTestResult result = connectorA().connect(ticket);

        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED_BY_PEER);
        assertThat(result.detail()).contains("UNAUTHORIZED");
    }

    @Test
    void expiredTicketIsRejected() {
        IssuedTicket ticket = ticketFromAToB();
        authority.expireAll();

        assertThat(connectorA().connect(ticket).outcome()).isEqualTo(Outcome.REJECTED_BY_PEER);
    }

    @Test
    void replayedTicketIsRejected() {
        IssuedTicket ticket = ticketFromAToB();

        assertThat(connectorA().connect(ticket).outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(connectorA().connect(ticket).outcome()).isEqualTo(Outcome.REJECTED_BY_PEER);
    }

    @Test
    void ticketForAnotherTargetIsRejected() {
        IssuedTicket forC = authority.issueTicket(peerA, fingerprint(tlsA), UUID.randomUUID(), addressOf(listenerB),
                fingerprint(tlsB), null);

        assertThat(connectorA().connect(forC).outcome()).isEqualTo(Outcome.REJECTED_BY_PEER);
    }

    @Test
    void claimingAnotherPeersIdentityIsRejected() throws Exception {
        IssuedTicket ticketOfA = ticketFromAToB();

        try (RawPeer raw = rawPeer(tlsA)) {
            raw.magic().send(Handshake.hello(UUID.randomUUID(), ticketOfA.ticket()));
            assertRejected(raw.receive(), RejectReason.UNAUTHORIZED);
        }
    }

    @Test
    void stolenTicketPresentedWithAnotherTlsKeyIsRejected() throws Exception {
        // The ticket is bound to A's certificate; someone else holding it cannot use it.
        IssuedTicket ticketOfA = ticketFromAToB();
        PeerTls thief = new PeerTls(TlsIdentity.generate("thief"));

        try (RawPeer raw = rawPeer(thief)) {
            raw.magic().send(Handshake.hello(peerA, ticketOfA.ticket()));
            assertRejected(raw.receive(), RejectReason.UNAUTHORIZED);
        }
    }

    @Test
    void stolenTicketPresentedWithAnotherKeyDoesNotBurnTheLegitimateConnection() throws Exception {
        IssuedTicket ticketOfA = ticketFromAToB();
        try (RawPeer raw = rawPeer(new PeerTls(TlsIdentity.generate("thief")))) {
            raw.magic().send(Handshake.hello(peerA, ticketOfA.ticket()));
            assertRejected(raw.receive(), RejectReason.UNAUTHORIZED);
        }
        assertThat(authority.refusedForCertificate.get()).isEqualTo(1);

        // A, with its own key, can still use the very same ticket.
        assertThat(connectorA().connect(ticketOfA).outcome()).isEqualTo(Outcome.SUCCEEDED);
    }

    @Test
    void listenerChecksTheCertificateItselfEvenIfTheServerDidNot() throws Exception {
        authority.presentedWithAnotherCertificateAllowed = true; // a server that skips its check
        IssuedTicket ticketOfA = ticketFromAToB();
        try (RawPeer raw = rawPeer(new PeerTls(TlsIdentity.generate("thief")))) {
            raw.magic().send(Handshake.hello(peerA, ticketOfA.ticket()));
            assertRejected(raw.receive(), RejectReason.UNAUTHORIZED);
        }
    }

    @Test
    void impostorWithItsOwnCertificateFailsTheTlsHandshake() throws Exception {
        PeerTls impostorTls = new PeerTls(TlsIdentity.generate("impostor"));
        try (ServerSocket impostor = new ServerSocket(0, 5, LOOPBACK)) {
            Thread.ofVirtual().start(() -> {
                try (SSLSocket socket = impostorTls.serverSide(impostor.accept())) {
                    socket.startHandshake();
                } catch (IOException e) {
                    // the connector aborts the handshake
                }
            });
            IssuedTicket ticket = authority.issueTicket(peerA, fingerprint(tlsA), peerB,
                    new InetSocketAddress(LOOPBACK, impostor.getLocalPort()), fingerprint(tlsB), null);

            ConnectionTestResult result = connectorA().connect(ticket);

            assertThat(result.outcome()).isEqualTo(Outcome.PEER_IDENTITY_MISMATCH);
            assertThat(result.detail()).contains("TLS certificate");
        }
    }

    @Test
    void responderWithTheRightKeyButWithoutTheServerProofIsRefused() throws Exception {
        // Even holding B's TLS key, a responder that cannot redeem the ticket cannot know the proof.
        try (ServerSocket impostor = new ServerSocket(0, 5, LOOPBACK)) {
            Thread.ofVirtual().start(() -> {
                try (SSLSocket socket = tlsB.serverSide(impostor.accept())) {
                    socket.startHandshake();
                    InputStream in = socket.getInputStream();
                    FrameIO.readMagic(in);
                    FrameIO.readFrame(in);
                    FrameIO.writeControl(socket.getOutputStream(),
                            MessageCodec.encode(Handshake.helloAck(2, peerB, "x".repeat(43))));
                    in.read();
                } catch (Exception e) {
                    // the test only cares about the connector's verdict
                }
            });
            IssuedTicket ticket = authority.issueTicket(peerA, fingerprint(tlsA), peerB,
                    new InetSocketAddress(LOOPBACK, impostor.getLocalPort()), fingerprint(tlsB), null);

            ConnectionTestResult result = connectorA().connect(ticket);

            assertThat(result.outcome()).isEqualTo(Outcome.PEER_IDENTITY_MISMATCH);
            assertThat(result.detail()).contains("could not prove");
        }
    }

    @Test
    void unverifiableTicketFailsClosedWhenServerIsDown() {
        IssuedTicket ticket = ticketFromAToB();
        authority.serverDown = true;

        ConnectionTestResult result = connectorA().connect(ticket);

        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED_BY_PEER);
        assertThat(result.detail()).contains("UNAVAILABLE");
    }

    @Test
    void ticketRequestFailuresAreReportedWithoutConnecting() {
        assertThat(connectorA().testConnection(UUID.randomUUID()).outcome()).isEqualTo(Outcome.TARGET_UNAVAILABLE);
        authority.serverDown = true;
        assertThat(connectorA().testConnection(peerB).outcome()).isEqualTo(Outcome.SERVER_UNAVAILABLE);
        assertThat(authority.reportedOutcomes).isEmpty();
    }

    // --- Malformed traffic and protocol errors ----------------------------------------

    @Test
    void unsupportedVersionIsRejected() throws Exception {
        IssuedTicket ticket = ticketFromAToB();
        try (RawPeer raw = rawPeer(tlsA)) {
            raw.magic().send(Message.of(MessageType.HELLO, "versions", "1,3", "peerId", peerA.toString(),
                    "ticket", ticket.ticket()));
            assertRejected(raw.receive(), RejectReason.UNSUPPORTED_VERSION);
        }
    }

    @Test
    void unknownMessageTypeIsRejected() throws Exception {
        try (RawPeer raw = rawPeer(tlsA)) {
            raw.magic().frame("type=SEND_FILE\n");
            assertRejected(raw.receive(), RejectReason.UNKNOWN_MESSAGE_TYPE);
        }
    }

    @Test
    void malformedIdentityIsRejected() throws Exception {
        try (RawPeer raw = rawPeer(tlsA)) {
            raw.magic().frame("type=HELLO\nversions=2\npeerId=../../etc\nticket=plt_x\n");
            assertRejected(raw.receive(), RejectReason.MALFORMED);
        }
    }

    @Test
    void oversizedFrameIsRejectedWithoutReadingIt() throws Exception {
        try (RawPeer raw = rawPeer(tlsA)) {
            raw.magic().raw(ByteBuffer.allocate(5).put(FrameIO.CONTROL).putInt(50 * 1024 * 1024).array());
            assertRejected(raw.receive(), RejectReason.FRAME_TOO_LARGE);
        }
    }

    @Test
    void dataFrameBeforeTheHandshakeIsRejected() throws Exception {
        try (RawPeer raw = rawPeer(tlsA)) {
            raw.magic().data(0, new byte[] {1, 2, 3});
            assertRejected(raw.receive(), RejectReason.UNEXPECTED_MESSAGE);
        }
    }

    @Test
    void plaintextTrafficIsRefusedAndTheListenerKeepsWorking() throws Exception {
        try (Socket plain = new Socket()) {
            plain.connect(addressOf(listenerB), 2000);
            plain.setSoTimeout(5000);
            plain.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes());
            assertThat(drainUntilClosed(plain)).isTrue();
        }

        assertThat(connectorA().testConnection(peerB).outcome()).isEqualTo(Outcome.SUCCEEDED);
    }

    @Test
    void clientDisconnectingMidHandshakeDoesNotAffectOthers() throws Exception {
        try (RawPeer raw = rawPeer(tlsA)) {
            raw.magic().raw(new byte[] {FrameIO.CONTROL, 0, 0, 0, 40, 't', 'y', 'p'}); // promises 40 bytes, sends 3
        }

        assertThat(connectorA().testConnection(peerB).outcome()).isEqualTo(Outcome.SUCCEEDED);
        awaitIdle(listenerB);
    }

    @Test
    void messagesOutOfOrderEndTheConnection() throws Exception {
        try (RawPeer raw = rawPeer(tlsA)) {
            raw.magic().send(Handshake.ping("abc"));
            assertRejected(raw.receive(), RejectReason.UNEXPECTED_MESSAGE);
        }
    }

    @Test
    void connectionTestsPerConnectionAreLimited() throws Exception {
        try (RawPeer raw = establish(addressOf(listenerB), tlsB)) {
            for (int i = 0; i < 16; i++) {
                raw.send(Handshake.ping("n" + i));
                assertThat(Handshake.nonce(raw.receive())).isEqualTo("n" + i);
            }
            raw.send(Handshake.ping("one-too-many"));
            Message bye = raw.receive();
            assertThat(bye.type()).isEqualTo(MessageType.BYE);
            assertThat(bye.optionalField("reason")).isEqualTo("TOO_MANY_MESSAGES");
        }
    }

    // --- Timeouts and failures ---------------------------------------------------------

    @Test
    void unavailableDestinationFailsFast() throws Exception {
        int freePort;
        try (ServerSocket probe = new ServerSocket(0, 1, LOOPBACK)) {
            freePort = probe.getLocalPort();
        }
        IssuedTicket ticket = authority.issueTicket(peerA, fingerprint(tlsA), peerB,
                new InetSocketAddress(LOOPBACK, freePort), fingerprint(tlsB), null);

        ConnectionTestResult result = connectorA().connect(ticket);

        assertThat(result.outcome()).isEqualTo(Outcome.CONNECT_FAILED);
        assertThat(result.detail()).contains("listening");
    }

    @Test
    void connectTimeoutIsBounded() {
        // 10.255.255.1 is non-routable: the attempt either times out or fails at once, never hangs.
        IssuedTicket ticket = authority.issueTicket(peerA, fingerprint(tlsA), peerB,
                new InetSocketAddress("10.255.255.1", 9), fingerprint(tlsB), null);
        PeerConnector connector = new PeerConnector(peerA, authority.issuerFor(peerA, fingerprint(tlsA)), tlsA,
                new PeerConnector.Settings(Duration.ofMillis(300), SHORT));

        long start = System.nanoTime();
        ConnectionTestResult result = connector.connect(ticket);

        assertThat(result.outcome()).isIn(Outcome.TIMEOUT, Outcome.CONNECT_FAILED);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void silentResponderCausesHandshakeTimeout() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 5, LOOPBACK)) {
            Thread.ofVirtual().start(() -> {
                try (Socket ignored = silent.accept()) {
                    Thread.sleep(10_000);
                } catch (Exception e) {
                    // closed at the end of the test
                }
            });
            IssuedTicket ticket = authority.issueTicket(peerA, fingerprint(tlsA), peerB,
                    new InetSocketAddress(LOOPBACK, silent.getLocalPort()), fingerprint(tlsB), null);
            PeerConnector connector = new PeerConnector(peerA, authority.issuerFor(peerA, fingerprint(tlsA)), tlsA,
                    new PeerConnector.Settings(Duration.ofSeconds(2), SHORT));

            long start = System.nanoTime();
            ConnectionTestResult result = connector.connect(ticket);

            assertThat(result.outcome()).isEqualTo(Outcome.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        }
    }

    @Test
    void listenerDropsClientsThatStallOrTrickleDuringTheHandshake() throws Exception {
        PeerTls strictTls = new PeerTls(TlsIdentity.generate("strict"));
        PeerListener strict = startListener(peerB, strictTls, authority.verifierFor(peerB), 2, SHORT);
        InetSocketAddress address = addressOf(strict);

        // Never even starts TLS.
        try (Socket silent = new Socket()) {
            silent.connect(address, 2000);
            silent.setSoTimeout(5000);
            long start = System.nanoTime();
            assertThat(drainUntilClosed(silent)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        }
        // Completes TLS, then trickles one byte at a time.
        try (RawPeer trickle = new RawPeer(address, tlsA, fingerprint(strictTls))) {
            long start = System.nanoTime();
            trickle.magic();
            for (int i = 0; i < 10; i++) {
                try {
                    trickle.raw(new byte[] {FrameIO.CONTROL});
                } catch (IOException e) {
                    break; // closed by the listener, as intended
                }
                Thread.sleep(100);
            }
            assertThat(trickle.closedByRemote()).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        }
        awaitIdle(strict);
    }

    @Test
    void connectionLimitRejectsExtraConnectionsAndRecovers() throws Exception {
        PeerTls limitedTls = new PeerTls(TlsIdentity.generate("limited"));
        PeerListener limited = startListener(peerB, limitedTls, authority.verifierFor(peerB), 2, Duration.ofSeconds(5));
        InetSocketAddress address = addressOf(limited);
        RawPeer first = establish(address, limitedTls);
        RawPeer second = establish(address, limitedTls);
        awaitActive(limited, 2);

        try (RawPeer third = new RawPeer(address, tlsA, fingerprint(limitedTls))) {
            assertRejected(third.receive(), RejectReason.BUSY);
        }

        first.send(Handshake.bye(null));
        first.close();
        awaitActive(limited, 1);
        try (RawPeer fourth = establish(address, limitedTls)) {
            assertThat(fourth).isNotNull();
        }
        second.close();
    }

    @Test
    void verifierFailureIsContainedAndListenerContinues() throws Exception {
        TicketVerifier exploding = (ticket, certificate) -> {
            throw new IllegalStateException("bug in verifier");
        };
        PeerTls fragileTls = new PeerTls(TlsIdentity.generate("fragile"));
        PeerListener fragile = startListener(peerB, fragileTls, exploding, 2, Duration.ofSeconds(5));
        InetSocketAddress address = addressOf(fragile);

        try (RawPeer raw = new RawPeer(address, tlsA, fingerprint(fragileTls))) {
            raw.magic().send(Handshake.hello(peerA, ticketFromAToB().ticket()));
            assertRejected(raw.receive(), RejectReason.INTERNAL_ERROR);
        }
        try (RawPeer raw = new RawPeer(address, tlsA, fingerprint(fragileTls))) {
            raw.magic().frame("type=NOPE\n");
            assertRejected(raw.receive(), RejectReason.UNKNOWN_MESSAGE_TYPE);
        }
    }

    // --- Shutdown ---------------------------------------------------------------------

    @Test
    void closeStopsAcceptingClosesConnectionsAndReleasesThePort() throws Exception {
        PeerTls closingTls = new PeerTls(TlsIdentity.generate("closing"));
        PeerListener listener = startListener(peerB, closingTls, authority.verifierFor(peerB), 2, Duration.ofSeconds(5));
        InetSocketAddress address = addressOf(listener);
        RawPeer established = establish(address, closingTls);

        listener.close();

        assertThat(listener.isClosed()).isTrue();
        assertThat(established.closedByRemote()).isTrue();
        established.close();
        try (ServerSocket rebind = new ServerSocket()) {
            rebind.setReuseAddress(true);
            rebind.bind(address);
            assertThat(rebind.isBound()).isTrue();
        }
        listener.close(); // idempotent
    }

    // --- Helpers ----------------------------------------------------------------------

    private PeerListener startListener(UUID self, PeerTls tls, TicketVerifier verifier, int maxConnections,
                                       Duration handshakeTimeout) throws Exception {
        PeerListener listener = new PeerListener(new PeerListener.Settings(LOOPBACK, 0, maxConnections),
                new InboundConnectionHandler(self, verifier, tls, null, handshakeTimeout, Duration.ofSeconds(5)), tls);
        listener.start();
        resources.add(listener);
        authority.register(self, addressOf(listener), fingerprint(tls));
        return listener;
    }

    private PeerConnector connectorA() {
        return new PeerConnector(peerA, authority.issuerFor(peerA, fingerprint(tlsA)), tlsA,
                PeerConnector.Settings.defaults());
    }

    private IssuedTicket ticketFromAToB() {
        return authority.issueTicket(peerA, fingerprint(tlsA), peerB, addressOf(listenerB), fingerprint(tlsB), null);
    }

    private RawPeer rawPeer(PeerTls clientTls) throws Exception {
        RawPeer raw = new RawPeer(addressOf(listenerB), clientTls, fingerprint(tlsB));
        resources.add(raw);
        return raw;
    }

    /** Completes a handshake as peer A and leaves the connection open (idle). */
    private RawPeer establish(InetSocketAddress address, PeerTls listenerTls) throws Exception {
        RawPeer raw = new RawPeer(address, tlsA, fingerprint(listenerTls));
        resources.add(raw);
        IssuedTicket ticket = authority.issueTicket(peerA, fingerprint(tlsA), peerB, address, fingerprint(listenerTls), null);
        raw.magic().send(Handshake.hello(peerA, ticket.ticket()));
        assertThat(raw.receive().type()).isEqualTo(MessageType.HELLO_ACK);
        return raw;
    }

    private static String fingerprint(PeerTls tls) {
        return tls.identity().fingerprint();
    }

    private static InetSocketAddress addressOf(PeerListener listener) {
        return new InetSocketAddress(LOOPBACK, listener.port());
    }

    private static boolean drainUntilClosed(Socket socket) {
        try {
            byte[] buffer = new byte[256];
            while (socket.getInputStream().read(buffer) >= 0) {
                // drain
            }
            return true;
        } catch (IOException e) {
            return String.valueOf(e.getMessage()).toLowerCase().contains("reset");
        }
    }

    private static void assertRejected(Message message, RejectReason reason) {
        assertThat(message.type()).isEqualTo(MessageType.REJECT);
        assertThat(message.optionalField("reason")).isEqualTo(reason.name());
    }

    private static void awaitActive(PeerListener listener, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (listener.activeConnections() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(listener.activeConnections()).isEqualTo(expected);
    }

    private static void awaitIdle(PeerListener listener) throws InterruptedException {
        awaitActive(listener, 0);
    }
}
