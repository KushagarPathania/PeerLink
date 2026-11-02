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
import com.peerlink.agent.security.TicketVerifier;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real sockets over loopback: agent A connects to agent B's listener. */
class PeerConnectionTests {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final Duration SHORT = Duration.ofMillis(400);

    private final UUID peerA = UUID.randomUUID();
    private final UUID peerB = UUID.randomUUID();
    private final InMemoryTicketAuthority authority = new InMemoryTicketAuthority();
    private final List<AutoCloseable> resources = new ArrayList<>();
    private PeerListener listenerB;

    @BeforeEach
    void startPeerB() throws Exception {
        listenerB = startListener(peerB, authority.verifierFor(peerB), 4, Duration.ofSeconds(5));
    }

    @AfterEach
    void closeAll() throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
    }

    // --- Successful connections --------------------------------------------------------

    @Test
    void twoAgentsCompleteHandshakeAndConnectionTest() {
        ConnectionTestResult result = connectorA().testConnection(peerB);

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(result.roundTrip()).isNotNull().isPositive();
        assertThat(result.detail()).contains(peerB.toString());
        assertThat(authority.reportedOutcomes).containsExactly(ConnectionOutcome.SUCCEEDED);
    }

    @Test
    void servesSeveralSimultaneousConnections() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Callable<ConnectionTestResult>> attempts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID source = UUID.randomUUID();
            attempts.add(() -> new PeerConnector(source, authority.issuerFor(source), PeerConnector.Settings.defaults())
                    .testConnection(peerB));
        }
        List<ConnectionTestResult> results = new ArrayList<>();
        for (Future<ConnectionTestResult> future : pool.invokeAll(attempts)) {
            results.add(future.get());
        }
        pool.shutdown();

        assertThat(results).extracting(ConnectionTestResult::outcome).containsOnly(Outcome.SUCCEEDED);
    }

    // --- Authorization -----------------------------------------------------------------

    @Test
    void invalidOrRevokedTicketIsRejected() {
        IssuedTicket ticket = authority.issueTicket(peerA, peerB, addressOf(listenerB));
        authority.revokeAll();

        ConnectionTestResult result = connectorA().connect(ticket);

        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED_BY_PEER);
        assertThat(result.detail()).contains("UNAUTHORIZED");
    }

    @Test
    void expiredTicketIsRejected() {
        IssuedTicket ticket = authority.issueTicket(peerA, peerB, addressOf(listenerB));
        authority.expireAll();

        assertThat(connectorA().connect(ticket).outcome()).isEqualTo(Outcome.REJECTED_BY_PEER);
    }

    @Test
    void replayedTicketIsRejected() {
        IssuedTicket ticket = authority.issueTicket(peerA, peerB, addressOf(listenerB));

        assertThat(connectorA().connect(ticket).outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(connectorA().connect(ticket).outcome()).isEqualTo(Outcome.REJECTED_BY_PEER);
    }

    @Test
    void ticketForAnotherTargetIsRejected() {
        // A ticket meant for peer C, presented to B (e.g. by someone who intercepted it).
        IssuedTicket forC = authority.issueTicket(peerA, UUID.randomUUID(), addressOf(listenerB));

        ConnectionTestResult result = connectorA().connect(forC);

        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED_BY_PEER);
    }

    @Test
    void claimingAnotherPeersIdentityIsRejected() throws Exception {
        IssuedTicket ticketOfA = authority.issueTicket(peerA, peerB, addressOf(listenerB));
        UUID impostor = UUID.randomUUID();

        try (RawPeer raw = rawPeer()) {
            raw.magic().send(Handshake.hello(impostor, ticketOfA.ticket()));
            assertRejected(raw.receive(), RejectReason.UNAUTHORIZED);
        }
    }

    @Test
    void responderThatCannotProveItsIdentityIsRefused() throws Exception {
        // An impostor listening where B should be: it accepts the handshake but cannot know B's proof.
        try (ServerSocket impostor = new ServerSocket(0, 5, LOOPBACK)) {
            Thread.ofVirtual().start(() -> {
                try (Socket socket = impostor.accept()) {
                    InputStream in = socket.getInputStream();
                    FrameIO.readMagic(in);
                    FrameIO.readFrame(in);
                    FrameIO.writeFrame(socket.getOutputStream(),
                            MessageCodec.encode(Handshake.helloAck(1, peerB, "x".repeat(43))));
                    in.read();
                } catch (Exception e) {
                    // the test only cares about the connector's verdict
                }
            });
            IssuedTicket ticket = authority.issueTicket(peerA, peerB,
                    new InetSocketAddress(LOOPBACK, impostor.getLocalPort()));

            ConnectionTestResult result = connectorA().connect(ticket);

            assertThat(result.outcome()).isEqualTo(Outcome.PEER_IDENTITY_MISMATCH);
        }
    }

    @Test
    void unverifiableTicketFailsClosedWhenServerIsDown() throws Exception {
        IssuedTicket ticket = authority.issueTicket(peerA, peerB, addressOf(listenerB));
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
        IssuedTicket ticket = authority.issueTicket(peerA, peerB, addressOf(listenerB));
        try (RawPeer raw = rawPeer()) {
            raw.magic().send(Message.of(MessageType.HELLO, "versions", "2,3", "peerId", peerA.toString(),
                    "ticket", ticket.ticket()));
            assertRejected(raw.receive(), RejectReason.UNSUPPORTED_VERSION);
        }
    }

    @Test
    void unknownMessageTypeIsRejected() throws Exception {
        try (RawPeer raw = rawPeer()) {
            raw.magic().frame("type=SEND_FILE\n");
            assertRejected(raw.receive(), RejectReason.UNKNOWN_MESSAGE_TYPE);
        }
    }

    @Test
    void malformedIdentityIsRejected() throws Exception {
        try (RawPeer raw = rawPeer()) {
            raw.magic().frame("type=HELLO\nversions=1\npeerId=../../etc\nticket=plt_x\n");
            assertRejected(raw.receive(), RejectReason.MALFORMED);
        }
    }

    @Test
    void oversizedFrameIsRejectedWithoutReadingIt() throws Exception {
        try (RawPeer raw = rawPeer()) {
            raw.magic().raw(ByteBuffer.allocate(4).putInt(50 * 1024 * 1024).array());
            assertRejected(raw.receive(), RejectReason.FRAME_TOO_LARGE);
        }
    }

    @Test
    void nonPeerLinkTrafficIsRejectedAndTheListenerKeepsWorking() throws Exception {
        try (RawPeer raw = rawPeer()) {
            raw.raw("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes());
            assertThat(raw.closedByRemote()).isTrue();
        }

        assertThat(connectorA().testConnection(peerB).outcome()).isEqualTo(Outcome.SUCCEEDED);
    }

    @Test
    void clientDisconnectingMidHandshakeDoesNotAffectOthers() throws Exception {
        try (RawPeer raw = rawPeer()) {
            raw.magic().raw(new byte[] {0, 0, 0, 40, 't', 'y', 'p'}); // promises 40 bytes, sends 3
        }

        assertThat(connectorA().testConnection(peerB).outcome()).isEqualTo(Outcome.SUCCEEDED);
        awaitIdle(listenerB);
    }

    @Test
    void messagesOutOfOrderEndTheConnection() throws Exception {
        try (RawPeer raw = rawPeer()) {
            raw.magic().send(Handshake.ping("abc"));
            assertRejected(raw.receive(), RejectReason.UNEXPECTED_MESSAGE);
        }
    }

    @Test
    void connectionTestsPerConnectionAreLimited() throws Exception {
        IssuedTicket ticket = authority.issueTicket(peerA, peerB, addressOf(listenerB));
        try (RawPeer raw = rawPeer()) {
            raw.magic().send(Handshake.hello(peerA, ticket.ticket()));
            assertThat(raw.receive().type()).isEqualTo(MessageType.HELLO_ACK);
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
        IssuedTicket ticket = authority.issueTicket(peerA, peerB, new InetSocketAddress(LOOPBACK, freePort));

        ConnectionTestResult result = connectorA().connect(ticket);

        assertThat(result.outcome()).isEqualTo(Outcome.CONNECT_FAILED);
        assertThat(result.detail()).contains("listening");
    }

    @Test
    void connectTimeoutIsBounded() {
        // 10.255.255.1 is non-routable: the attempt either times out or fails at once, never hangs.
        IssuedTicket ticket = authority.issueTicket(peerA, peerB, new InetSocketAddress("10.255.255.1", 9));
        PeerConnector connector = new PeerConnector(peerA, authority.issuerFor(peerA),
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
            IssuedTicket ticket = authority.issueTicket(peerA, peerB,
                    new InetSocketAddress(LOOPBACK, silent.getLocalPort()));
            PeerConnector connector = new PeerConnector(peerA, authority.issuerFor(peerA),
                    new PeerConnector.Settings(Duration.ofSeconds(2), SHORT));

            long start = System.nanoTime();
            ConnectionTestResult result = connector.connect(ticket);

            assertThat(result.outcome()).isEqualTo(Outcome.TIMEOUT);
            assertThat(result.detail()).contains("No reply");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        }
    }

    @Test
    void listenerDropsClientsThatStallOrTrickleDuringTheHandshake() throws Exception {
        PeerListener strict = startListener(peerB, authority.verifierFor(peerB), 2, SHORT);
        InetSocketAddress address = addressOf(strict);

        try (RawPeer silent = new RawPeer(address)) {
            long start = System.nanoTime();
            assertThat(silent.closedByRemote()).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        }
        try (RawPeer trickle = new RawPeer(address)) {
            long start = System.nanoTime();
            trickle.magic();
            for (int i = 0; i < 10 && !Thread.currentThread().isInterrupted(); i++) {
                try {
                    trickle.raw(new byte[] {0});
                } catch (java.io.IOException e) {
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
        PeerListener limited = startListener(peerB, authority.verifierFor(peerB), 2, Duration.ofSeconds(5));
        InetSocketAddress address = addressOf(limited);
        RawPeer first = establish(address);
        RawPeer second = establish(address);
        awaitActive(limited, 2);

        try (RawPeer third = new RawPeer(address)) {
            third.magic().send(Handshake.hello(peerA, authority.issueTicket(peerA, peerB, address).ticket()));
            assertRejected(third.receive(), RejectReason.BUSY);
        }

        first.send(Handshake.bye(null));
        first.close();
        awaitActive(limited, 1);
        try (RawPeer fourth = establish(address)) {
            assertThat(fourth).isNotNull();
        }
        second.close();
    }

    @Test
    void verifierFailureIsContainedAndListenerContinues() throws Exception {
        TicketVerifier exploding = ticket -> {
            throw new IllegalStateException("bug in verifier");
        };
        PeerListener fragile = startListener(peerB, exploding, 2, Duration.ofSeconds(5));
        InetSocketAddress address = addressOf(fragile);

        try (RawPeer raw = new RawPeer(address)) {
            raw.magic().send(Handshake.hello(peerA, authority.issueTicket(peerA, peerB, address).ticket()));
            assertRejected(raw.receive(), RejectReason.INTERNAL_ERROR);
        }
        try (RawPeer raw = new RawPeer(address)) {
            raw.magic().frame("type=NOPE\n");
            assertRejected(raw.receive(), RejectReason.UNKNOWN_MESSAGE_TYPE);
        }
    }

    // --- Shutdown ---------------------------------------------------------------------

    @Test
    void closeStopsAcceptingClosesConnectionsAndReleasesThePort() throws Exception {
        PeerListener listener = startListener(peerB, authority.verifierFor(peerB), 2, Duration.ofSeconds(5));
        InetSocketAddress address = addressOf(listener);
        RawPeer established = establish(address);

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

    private PeerListener startListener(UUID self, TicketVerifier verifier, int maxConnections, Duration handshakeTimeout)
            throws Exception {
        PeerListener listener = new PeerListener(new PeerListener.Settings(LOOPBACK, 0, maxConnections),
                new InboundConnectionHandler(self, verifier, handshakeTimeout, Duration.ofSeconds(5)));
        listener.start();
        resources.add(listener);
        authority.register(self, addressOf(listener));
        return listener;
    }

    private PeerConnector connectorA() {
        return new PeerConnector(peerA, authority.issuerFor(peerA), PeerConnector.Settings.defaults());
    }

    private RawPeer rawPeer() throws Exception {
        return new RawPeer(addressOf(listenerB));
    }

    /** Completes a handshake and leaves the connection open (idle). */
    private RawPeer establish(InetSocketAddress address) throws Exception {
        RawPeer raw = new RawPeer(address);
        resources.add(raw);
        raw.magic().send(Handshake.hello(peerA, authority.issueTicket(peerA, peerB, address).ticket()));
        assertThat(raw.receive().type()).isEqualTo(MessageType.HELLO_ACK);
        return raw;
    }

    private static InetSocketAddress addressOf(PeerListener listener) {
        return new InetSocketAddress(LOOPBACK, listener.port());
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
