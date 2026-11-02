package com.peerlink.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.peerlink.server.entity.ConnectionOutcome;
import com.peerlink.server.entity.ConnectionTicket;
import com.peerlink.server.entity.ConnectionTicketStatus;
import com.peerlink.server.entity.User;
import com.peerlink.server.repository.ConnectionTicketRepository;
import com.peerlink.server.security.PeerAgentPrincipal;
import com.peerlink.server.security.SecretTokens;
import com.peerlink.server.service.ConnectionTicketService;
import com.peerlink.server.support.WebIntegrationTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

@ExtendWith(OutputCaptureExtension.class)
class PeerConnectionApiIntegrationTests extends WebIntegrationTestSupport {

    @Autowired
    private ConnectionTicketRepository ticketRepository;

    @Autowired
    private ConnectionTicketService ticketService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockHttpSession aliceSession;
    private Agent a;
    private Agent b;

    /** A registered peer and its agent credential. */
    private record Agent(String peerId, String credential) {
        UUID id() {
            return UUID.fromString(peerId);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        String alice = uniqueUsername();
        createUser(alice);
        aliceSession = apiLogin(alice);
        String bob = uniqueUsername();
        createUser(bob);
        a = registerOnlinePeer(aliceSession, "alice-laptop", "192.168.1.10", 9090);
        b = registerOnlinePeer(apiLogin(bob), "bob-desktop", "192.168.1.20", 9191);
    }

    // --- Issuing ----------------------------------------------------------------------

    @Test
    void issuesSingleUseTicketWithTargetAddressAndStoresOnlyItsHash() throws Exception {
        Instant before = Instant.now();
        JsonNode ticket = issue(a, b.peerId())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourcePeerId").value(a.peerId()))
                .andExpect(jsonPath("$.targetPeerId").value(b.peerId()))
                .andExpect(jsonPath("$.targetHost").value("192.168.1.20"))
                .andExpect(jsonPath("$.targetPort").value(9191))
                .andReturn().getResponse().getContentAsString().transform(this::readTree);

        assertThat(ticket.get("ticket").asText()).matches("plt_[A-Za-z0-9_-]{43}");
        assertThat(ticket.get("responderProof").asText()).matches("[A-Za-z0-9_-]{43}");
        assertThat(Instant.parse(ticket.get("expiresAt").asText()))
                .isBetween(before.plusSeconds(59), Instant.now().plusSeconds(61));
        String storedHash = jdbcTemplate.queryForObject(
                "select ticket_hash from connection_tickets where id = ?::uuid", String.class,
                ticket.get("ticketId").asText());
        assertThat(storedHash).isEqualTo(SecretTokens.sha256Hex(ticket.get("ticket").asText()));
    }

    @Test
    void targetMustBeAnotherOnlinePeerOfAnEnabledAccount() throws Exception {
        String offlinePeer = registration(aliceSession, "never-online").at("/peer/id").asText();

        issue(a, a.peerId()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        issue(a, offlinePeer).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PEER_NOT_FOUND"));
        issue(a, UUID.randomUUID().toString()).andExpect(status().isNotFound());

        User bob = userRepository.findAll().stream()
                .filter(user -> user.getUsername().equals(ownerOf(b))).findFirst().orElseThrow();
        bob.disable();
        userRepository.saveAndFlush(bob);
        issue(a, b.peerId()).andExpect(status().isNotFound());
    }

    @Test
    void onlyThePeersOwnAgentMayRequestTickets() throws Exception {
        String body = json(Map.of("targetPeerId", b.peerId()));
        // Signed-in user (session, with CSRF), anonymous, and another peer's credential.
        mockMvc.perform(post("/api/peers/" + a.peerId() + "/connections").session(aliceSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/peers/" + a.peerId() + "/connections").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/peers/" + a.peerId() + "/connections").header(HttpHeaders.AUTHORIZATION, bearer(b))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    // --- Redeeming --------------------------------------------------------------------

    @Test
    void targetRedeemsTicketOnceAndLearnsVerifiedSourceIdentity(CapturedOutput output) throws Exception {
        JsonNode ticket = issued(a, b);

        redeem(b, ticket.get("ticket").asText())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(ticket.get("ticketId").asText()))
                .andExpect(jsonPath("$.sourcePeerId").value(a.peerId()))
                .andExpect(jsonPath("$.sourceDeviceName").value("alice-laptop"))
                .andExpect(jsonPath("$.responderProof").value(ticket.get("responderProof").asText()));

        ConnectionTicket stored = ticketRepository.findById(UUID.fromString(ticket.get("ticketId").asText())).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ConnectionTicketStatus.REDEEMED);
        assertThat(jdbcTemplate.queryForObject("select responder_proof from connection_tickets where id = ?",
                String.class, stored.getId())).isNull();

        // Replay: the same ticket can never be redeemed again.
        redeem(b, ticket.get("ticket").asText())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONNECTION_TICKET_INVALID"));

        assertThat(output).doesNotContain(ticket.get("ticket").asText())
                .doesNotContain(ticket.get("responderProof").asText());
    }

    @Test
    void ticketPresentedToTheWrongPeerIsRejectedAndBurned() throws Exception {
        Agent charlie = registerOnlinePeer(apiLogin(createdUsername()), "charlie-box", "192.168.1.30", 9090);
        String ticket = issued(a, b).get("ticket").asText();

        redeem(charlie, ticket).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CONNECTION_TICKET_INVALID"));
        // Invalidated: even the intended target can no longer use it.
        redeem(b, ticket).andExpect(status().isForbidden());
    }

    @Test
    void expiredTicketIsRejected() throws Exception {
        JsonNode ticket = issued(a, b);
        jdbcTemplate.update("update connection_tickets set issued_at = now() - interval '2 minutes', "
                + "expires_at = now() - interval '1 minute' where id = ?::uuid", ticket.get("ticketId").asText());

        redeem(b, ticket.get("ticket").asText()).andExpect(status().isForbidden());
    }

    @Test
    void rotatingOrRevokingTheSourceCredentialRevokesOutstandingTickets() throws Exception {
        String beforeRotation = issued(a, b).get("ticket").asText();
        String newCredential = mockMvc.perform(post("/api/peers/" + a.peerId() + "/credential")
                        .session(aliceSession).with(csrf()))
                .andReturn().getResponse().getContentAsString().transform(this::readTree).get("credential").asText();
        redeem(b, beforeRotation).andExpect(status().isForbidden());

        Agent rotated = new Agent(a.peerId(), newCredential);
        String beforeRevocation = issued(rotated, b).get("ticket").asText();
        mockMvc.perform(delete("/api/peers/" + a.peerId() + "/credential").session(aliceSession).with(csrf()))
                .andExpect(status().isNoContent());
        redeem(b, beforeRevocation).andExpect(status().isForbidden());
    }

    @Test
    void unknownMalformedOrMisdirectedRedemptionsAreRejected() throws Exception {
        String ticket = issued(a, b).get("ticket").asText();

        redeem(b, "plt_" + "A".repeat(43)).andExpect(status().isForbidden());
        redeem(b, "not-a-ticket").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("ticket"));
        // B's credential cannot redeem on A's behalf.
        mockMvc.perform(post("/api/peers/" + a.peerId() + "/connections/redeem").header(HttpHeaders.AUTHORIZATION, bearer(b))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("ticket", ticket))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        redeem(b, ticket).andExpect(status().isOk()); // still unused after the rejected attempts above
    }

    @Test
    void concurrentRedemptionsSucceedExactlyOnce() throws Exception {
        String ticket = issued(a, b).get("ticket").asText();
        PeerAgentPrincipal bAgent = new PeerAgentPrincipal(b.id(), UUID.randomUUID());
        int attempts = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    ticketService.redeem(bAgent, b.id(), ticket);
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int successes = 0;
        for (Future<Boolean> result : results) {
            successes += result.get() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(successes).isEqualTo(1);
    }

    // --- Outcomes and heartbeat port --------------------------------------------------

    @Test
    void sourceReportsOutcomeOnce() throws Exception {
        String ticketId = issued(a, b).get("ticketId").asText();

        reportOutcome(a, ticketId, "SUCCEEDED").andExpect(status().isNoContent());
        assertThat(ticketRepository.findById(UUID.fromString(ticketId)).orElseThrow().getOutcome())
                .isEqualTo(ConnectionOutcome.SUCCEEDED);
        reportOutcome(a, ticketId, "TIMEOUT").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUTCOME_ALREADY_REPORTED"));
    }

    @Test
    void outcomeCanOnlyBeReportedBySourceWithAKnownValue() throws Exception {
        String ticketId = issued(a, b).get("ticketId").asText();

        reportOutcome(b, ticketId, "SUCCEEDED").andExpect(status().isNotFound());
        reportOutcome(a, ticketId, "EXPLODED").andExpect(status().isBadRequest());
        reportOutcome(a, UUID.randomUUID().toString(), "SUCCEEDED").andExpect(status().isNotFound());
    }

    @Test
    void heartbeatCanReportListeningPortButNotAnInvalidOne() throws Exception {
        heartbeat(a, "192.168.1.10", Map.of("listeningPort", 9300)).andExpect(status().isOk());
        mockMvc.perform(get("/api/peers/" + a.peerId()).session(aliceSession))
                .andExpect(jsonPath("$.listeningPort").value(9300));

        heartbeat(a, "192.168.1.10", Map.of("listeningPort", 70000))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("listeningPort"));
    }

    // --- Helpers ----------------------------------------------------------------------

    private Agent registerOnlinePeer(MockHttpSession session, String deviceName, String address, int port)
            throws Exception {
        JsonNode registration = registration(session, deviceName);
        Agent agent = new Agent(registration.at("/peer/id").asText(),
                registration.at("/agentCredential/credential").asText());
        heartbeat(agent, address, Map.of("listeningPort", port)).andExpect(status().isOk());
        return agent;
    }

    private JsonNode registration(MockHttpSession session, String deviceName) throws Exception {
        return readTree(mockMvc.perform(post("/api/peers").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("deviceName", deviceName, "listeningPort", 9090))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions heartbeat(Agent agent, String remoteAddress, Map<String, ?> body) throws Exception {
        return mockMvc.perform(post("/api/peers/" + agent.peerId() + "/heartbeat")
                .header(HttpHeaders.AUTHORIZATION, bearer(agent))
                .with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions issue(Agent source, String targetPeerId) throws Exception {
        return mockMvc.perform(post("/api/peers/" + source.peerId() + "/connections")
                .header(HttpHeaders.AUTHORIZATION, bearer(source))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("targetPeerId", targetPeerId))));
    }

    private JsonNode issued(Agent source, Agent target) throws Exception {
        return readTree(issue(source, target.peerId()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions redeem(Agent target, String ticket) throws Exception {
        return mockMvc.perform(post("/api/peers/" + target.peerId() + "/connections/redeem")
                .header(HttpHeaders.AUTHORIZATION, bearer(target))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("ticket", ticket))));
    }

    private ResultActions reportOutcome(Agent source, String ticketId, String outcome) throws Exception {
        return mockMvc.perform(post("/api/peers/" + source.peerId() + "/connections/" + ticketId + "/outcome")
                .header(HttpHeaders.AUTHORIZATION, bearer(source))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("outcome", outcome))));
    }

    private String ownerOf(Agent agent) {
        return jdbcTemplate.queryForObject("select u.username from peers p join users u on u.id = p.user_id "
                + "where p.id = ?::uuid", String.class, agent.peerId());
    }

    private String createdUsername() {
        String username = uniqueUsername();
        createUser(username);
        return username;
    }

    private static String bearer(Agent agent) {
        return "Bearer " + agent.credential();
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
