package com.peerlink.agent.networking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peerlink.agent.config.AgentCredential;
import com.peerlink.agent.networking.CoordinationServerClient.ServerStatus;
import com.peerlink.agent.presence.HeartbeatResult;
import com.peerlink.agent.presence.HeartbeatResult.Outcome;
import com.peerlink.agent.security.ConnectionOutcome;
import com.peerlink.agent.security.IssuedTicket;
import com.peerlink.agent.security.TicketException;
import com.peerlink.agent.security.VerifiedTicket;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Exercises the client against a real local HTTP server rather than a mocked HttpClient. */
class CoordinationServerClientTests {

    private static final AgentCredential CREDENTIAL = AgentCredential.of("plk_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");
    private static final UUID PEER_ID = UUID.fromString("3f2b8c1e-6d4a-4b7e-9c2d-1a5e8f7b6c3d");

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void reportsHealthyOn200() throws IOException {
        URI url = startServerResponding(200);

        assertThat(new CoordinationServerClient(url).checkHealth()).isEqualTo(ServerStatus.HEALTHY);
    }

    @Test
    void reportsUnhealthyOn503() throws IOException {
        URI url = startServerResponding(503);

        assertThat(new CoordinationServerClient(url).checkHealth()).isEqualTo(ServerStatus.UNHEALTHY);
    }

    @Test
    void reportsUnreachableWhenNothingIsListening() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }

        URI url = URI.create("http://127.0.0.1:" + freePort);

        assertThat(new CoordinationServerClient(url).checkHealth()).isEqualTo(ServerStatus.UNREACHABLE);
    }

    @Test
    void heartbeatSendsBearerCredentialAndParsesObservedAddress() throws IOException {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        URI url = startServer("/api/peers/" + PEER_ID + "/heartbeat", 200,
                "{\"peerId\":\"" + PEER_ID + "\",\"status\":\"ONLINE\",\"observedAddress\":\"192.168.1.20\"}",
                authorization, method);

        HeartbeatResult result = new CoordinationServerClient(url).sendHeartbeat(PEER_ID, CREDENTIAL, null, null);

        assertThat(result.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(result.observedAddress()).isEqualTo("192.168.1.20");
        assertThat(authorization.get()).isEqualTo("Bearer " + CREDENTIAL.value());
        assertThat(method.get()).isEqualTo("POST");
    }

    @Test
    void heartbeatClassifiesServerResponses() throws IOException {
        assertThat(heartbeatAgainstStatus(401).outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(heartbeatAgainstStatus(403).outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(heartbeatAgainstStatus(503).outcome()).isEqualTo(Outcome.SERVER_ERROR);
        assertThat(heartbeatAgainstStatus(429).outcome()).isEqualTo(Outcome.SERVER_ERROR);
    }

    @Test
    void heartbeatReportsUnreachableServerWithoutThrowing() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }

        HeartbeatResult result = new CoordinationServerClient(URI.create("http://127.0.0.1:" + freePort))
                .sendHeartbeat(PEER_ID, CREDENTIAL, null, null);

        assertThat(result.outcome()).isEqualTo(Outcome.UNREACHABLE);
    }

    @Test
    void heartbeatTimesOutWhenServerNeverAnswers() throws Exception {
        // Accepts the TCP connection but never sends an HTTP response.
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try (Socket ignored = silent.accept()) {
                    Thread.sleep(10_000);
                } catch (Exception e) {
                    // closed when the test ends
                }
            });
            CoordinationServerClient client = new CoordinationServerClient(
                    URI.create("http://127.0.0.1:" + silent.getLocalPort()), Duration.ofMillis(300));

            long start = System.nanoTime();
            HeartbeatResult result = client.sendHeartbeat(PEER_ID, CREDENTIAL, null, null);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(result.outcome()).isEqualTo(Outcome.UNREACHABLE);
            assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
            acceptor.interrupt();
        }
    }

    private static final UUID TARGET = UUID.fromString("9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d");
    private static final String TICKET = "plt_" + "T".repeat(43);
    private static final String PROOF = "P".repeat(43);
    private static final String TARGET_CERT = "d".repeat(64);
    private static final String OWN_CERT = "e".repeat(64);

    @Test
    void heartbeatReportsListeningPortInBody() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        new CoordinationServerClient(baseUrl()).sendHeartbeat(PEER_ID, CREDENTIAL, 9444, "c".repeat(64));

        assertThat(body.get()).isEqualTo("{\"listeningPort\":9444,\"tlsCertificateSha256\":\"" + "c".repeat(64) + "\"}");
    }

    @Test
    void requestsAndParsesConnectionTicket() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        URI url = startServer("/api/peers/" + PEER_ID + "/connections", 201, ticketJson("192.168.1.20"),
                authorization, new AtomicReference<>());

        IssuedTicket ticket = new CoordinationServerClient(url).requestConnectionTicket(PEER_ID, CREDENTIAL, TARGET,
                null, OWN_CERT);

        assertThat(ticket.ticket()).isEqualTo(TICKET);
        assertThat(ticket.targetCertificateSha256()).isEqualTo(TARGET_CERT);
        assertThat(ticket.targetHost()).isEqualTo("192.168.1.20");
        assertThat(ticket.targetPort()).isEqualTo(9191);
        assertThat(ticket.responderProof()).isEqualTo(PROOF);
        assertThat(ticket.toString()).doesNotContain(TICKET).doesNotContain(PROOF);
        assertThat(authorization.get()).isEqualTo("Bearer " + CREDENTIAL.value());
    }

    @Test
    void ticketResponseNamingAHostNameIsRejectedWithoutDnsLookup() throws Exception {
        URI url = startServer("/api/peers/" + PEER_ID + "/connections", 201, ticketJson("attacker.example.com"),
                new AtomicReference<>(), new AtomicReference<>());

        assertThatThrownBy(() -> new CoordinationServerClient(url).requestConnectionTicket(PEER_ID, CREDENTIAL, TARGET, null, OWN_CERT))
                .isInstanceOfSatisfying(TicketException.class,
                        e -> assertThat(e.reason()).isEqualTo(TicketException.Reason.INVALID_RESPONSE));
    }

    @Test
    void ticketRequestErrorsAreClassified() throws Exception {
        assertTicketRequestFails(404, TicketException.Reason.TARGET_UNAVAILABLE);
        assertTicketRequestFails(401, TicketException.Reason.CREDENTIAL_REJECTED);
        assertTicketRequestFails(500, TicketException.Reason.SERVER_UNAVAILABLE);
    }

    @Test
    void redemptionSendsThePresentedTlsCertificate() throws Exception {
        AtomicReference<String> sent = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> new CoordinationServerClient(baseUrl()).redeemConnectionTicket(PEER_ID, CREDENTIAL, TICKET,
                OWN_CERT)).isInstanceOf(TicketException.class);
        // The server checks it before consuming the ticket.
        assertThat(sent.get()).contains("\"presentedCertificateSha256\":\"" + OWN_CERT + "\"");
    }

    @Test
    void redeemsTicketAndClassifiesRejection() throws Exception {
        String body = "{\"ticketId\":\"" + UUID.randomUUID() + "\",\"sourcePeerId\":\"" + TARGET
                + "\",\"sourceDeviceName\":\"laptop\",\"sourceOwnerUsername\":\"alice\",\"responderProof\":\""
                + PROOF + "\",\"initiatorCertificateSha256\":\"" + OWN_CERT + "\",\"transferId\":null}";
        URI url = startServer("/api/peers/" + PEER_ID + "/connections/redeem", 200, body,
                new AtomicReference<>(), new AtomicReference<>());

        VerifiedTicket verified = new CoordinationServerClient(url).redeemConnectionTicket(PEER_ID, CREDENTIAL, TICKET, OWN_CERT);
        assertThat(verified.sourcePeerId()).isEqualTo(TARGET);
        assertThat(verified.responderProof()).isEqualTo(PROOF);
        assertThat(verified.initiatorCertificateSha256()).isEqualTo(OWN_CERT);
        assertThat(verified.transfer()).isNull();

        stopServer();
        URI rejecting = startServer("/api/peers/" + PEER_ID + "/connections/redeem", 403, "{}",
                new AtomicReference<>(), new AtomicReference<>());
        assertThatThrownBy(() -> new CoordinationServerClient(rejecting).redeemConnectionTicket(PEER_ID, CREDENTIAL, TICKET, OWN_CERT))
                .isInstanceOfSatisfying(TicketException.class,
                        e -> assertThat(e.reason()).isEqualTo(TicketException.Reason.INVALID_TICKET));
    }

    @Test
    void malformedTicketIsNeverSentToTheServer() {
        CoordinationServerClient client = new CoordinationServerClient(URI.create("http://127.0.0.1:1"));

        assertThatThrownBy(() -> client.redeemConnectionTicket(PEER_ID, CREDENTIAL, "plt_\"},\"x\":\"", OWN_CERT))
                .isInstanceOfSatisfying(TicketException.class,
                        e -> assertThat(e.reason()).isEqualTo(TicketException.Reason.INVALID_TICKET));
    }

    @Test
    void reportsOutcome() throws Exception {
        UUID ticketId = UUID.randomUUID();
        URI url = startServer("/api/peers/" + PEER_ID + "/connections/" + ticketId + "/outcome", 204, "",
                new AtomicReference<>(), new AtomicReference<>());

        assertThat(new CoordinationServerClient(url)
                .reportConnectionOutcome(PEER_ID, CREDENTIAL, ticketId, ConnectionOutcome.SUCCEEDED)).isTrue();
    }

    private void assertTicketRequestFails(int status, TicketException.Reason reason) throws IOException {
        stopServer();
        URI url = startServer("/api/peers/" + PEER_ID + "/connections", status, "{}",
                new AtomicReference<>(), new AtomicReference<>());
        assertThatThrownBy(() -> new CoordinationServerClient(url).requestConnectionTicket(PEER_ID, CREDENTIAL, TARGET, null, OWN_CERT))
                .isInstanceOfSatisfying(TicketException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private static String ticketJson(String host) {
        return "{\"ticketId\":\"" + UUID.randomUUID() + "\",\"ticket\":\"" + TICKET + "\",\"sourcePeerId\":\""
                + PEER_ID + "\",\"targetPeerId\":\"" + TARGET + "\",\"targetHost\":\"" + host
                + "\",\"targetPort\":9191,\"expiresAt\":\"2026-11-02T10:00:00Z\",\"responderProof\":\"" + PROOF
                + "\",\"targetCertificateSha256\":\"" + TARGET_CERT + "\",\"transferId\":null}";
    }

    private URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private HeartbeatResult heartbeatAgainstStatus(int status) throws IOException {
        stopServer();
        URI url = startServer("/api/peers/" + PEER_ID + "/heartbeat", status, "{}", new AtomicReference<>(),
                new AtomicReference<>());
        return new CoordinationServerClient(url).sendHeartbeat(PEER_ID, CREDENTIAL, null, null);
    }

    private URI startServer(String path, int status, String body, AtomicReference<String> authorization,
                            AtomicReference<String> method) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(path, exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private URI startServerResponding(int statusCode) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/health", exchange -> {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
}
