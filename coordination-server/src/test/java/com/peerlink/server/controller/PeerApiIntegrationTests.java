package com.peerlink.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.PeerStatus;
import com.peerlink.server.entity.User;
import com.peerlink.server.repository.PeerRepository;
import com.peerlink.server.support.WebIntegrationTestSupport;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@ExtendWith(OutputCaptureExtension.class)
class PeerApiIntegrationTests extends WebIntegrationTestSupport {

    @Autowired
    private PeerRepository peerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String alice;
    private MockHttpSession aliceSession;

    @BeforeEach
    void signInAlice() throws Exception {
        alice = uniqueUsername();
        createUser(alice);
        aliceSession = apiLogin(alice);
    }

    // --- Registration ---------------------------------------------------------------

    @Test
    void registersPeerForSessionUserAndReturnsCredentialOnce() throws Exception {
        JsonNode body = registerPeer(aliceSession, Map.of("deviceName", "laptop", "listeningPort", 9090))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/peers/")))
                .andExpect(jsonPath("$.peer.ownerUsername").value(alice))
                .andExpect(jsonPath("$.peer.deviceName").value("laptop"))
                .andExpect(jsonPath("$.peer.listeningPort").value(9090))
                .andExpect(jsonPath("$.peer.status").value("OFFLINE"))
                .andExpect(jsonPath("$.peer.hostAddress").isEmpty())
                .andExpect(jsonPath("$.peer.agentCredentialActive").value(true))
                .andReturn().getResponse().getContentAsString().transform(this::readTree);

        String credential = body.at("/agentCredential/credential").asText();
        assertThat(credential).startsWith("plk_").hasSizeGreaterThan(40);
        String storedHash = jdbcTemplate.queryForObject("select credential_hash from peers where id = ?::uuid",
                String.class, body.at("/peer/id").asText());
        assertThat(storedHash).isEqualTo(sha256(credential)).isNotEqualTo(credential);

        // Later reads never contain the credential or its hash.
        String mine = mockMvc.perform(get("/api/peers/mine").session(aliceSession))
                .andReturn().getResponse().getContentAsString();
        assertThat(mine).doesNotContain(credential).doesNotContain(storedHash).doesNotContainIgnoringCase("hash");
    }

    @Test
    void ownerAndAddressAreNeverTakenFromTheRequestBody() throws Exception {
        User bob = createUser(uniqueUsername());
        Map<String, Object> body = new HashMap<>();
        body.put("deviceName", "sneaky");
        body.put("listeningPort", 9090);
        body.put("userId", bob.getId().toString());
        body.put("ownerId", bob.getId().toString());
        body.put("hostAddress", "203.0.113.7");
        body.put("status", "ONLINE");

        registerPeer(aliceSession, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.peer.ownerUsername").value(alice))
                .andExpect(jsonPath("$.peer.hostAddress").isEmpty())
                .andExpect(jsonPath("$.peer.status").value("OFFLINE"));
        assertThat(peerRepository.findByUserIdOrderByDeviceNameAsc(bob.getId())).isEmpty();
    }

    @Test
    void anonymousRegistrationIsRejected() throws Exception {
        registerPeer(new MockHttpSession(), Map.of("deviceName", "laptop", "listeningPort", 9090))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationRequiresCsrfToken() throws Exception {
        mockMvc.perform(post("/api/peers").session(aliceSession).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("deviceName", "laptop", "listeningPort", 9090))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void rejectsInvalidPortsAndMissingDeviceName() throws Exception {
        for (int port : new int[] {0, 65536, -1}) {
            registerPeer(aliceSession, Map.of("deviceName", "laptop", "listeningPort", port))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("listeningPort"));
        }
        registerPeer(aliceSession, Map.of("listeningPort", 9090))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("deviceName"));
        registerPeer(aliceSession, Map.of("deviceName", "   ", "listeningPort", 9090))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsDuplicateDeviceNameForSameUser() throws Exception {
        registerPeer(aliceSession, Map.of("deviceName", "desktop", "listeningPort", 9090)).andExpect(status().isCreated());

        registerPeer(aliceSession, Map.of("deviceName", "desktop", "listeningPort", 9091))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_NAME_TAKEN"));
    }

    // --- Ownership ------------------------------------------------------------------

    @Test
    void usersSeeOnlyTheirOwnPeers() throws Exception {
        String laptopId = registeredPeerId(aliceSession, "laptop");
        String phoneId = registeredPeerId(aliceSession, "phone");
        String bob = uniqueUsername();
        createUser(bob);
        MockHttpSession bobSession = apiLogin(bob);
        String bobPeerId = registeredPeerId(bobSession, "bob-box");

        mockMvc.perform(get("/api/peers/mine").session(aliceSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.containsInAnyOrder(laptopId, phoneId)));
        mockMvc.perform(get("/api/peers/" + laptopId).session(aliceSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceName").value("laptop"));

        // Someone else's peer looks exactly like one that does not exist.
        String othersPeer = mockMvc.perform(get("/api/peers/" + bobPeerId).session(aliceSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PEER_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        String missingPeer = mockMvc.perform(get("/api/peers/" + UUID.randomUUID()).session(aliceSession))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(readTree(othersPeer).get("detail")).isEqualTo(readTree(missingPeer).get("detail"));

        mockMvc.perform(post("/api/peers/" + bobPeerId + "/credential").session(aliceSession).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/peers/" + bobPeerId + "/credential").session(aliceSession).with(csrf()))
                .andExpect(status().isNotFound());
    }

    // --- Heartbeat ------------------------------------------------------------------

    @Test
    void heartbeatUsesServerTimeAndObservedAddressAndIgnoresClientClaims(CapturedOutput output) throws Exception {
        JsonNode registration = registration(aliceSession, "laptop");
        String peerId = registration.at("/peer/id").asText();
        String credential = registration.at("/agentCredential/credential").asText();
        Instant before = Instant.now();

        mockMvc.perform(heartbeat(peerId, credential)
                        .with(remoteAddress("192.168.1.50"))
                        .header("X-Forwarded-For", "203.0.113.9")
                        .header(HttpHeaders.DATE, "Sat, 01 Jan 2000 00:00:00 GMT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("timestamp", "2000-01-01T00:00:00Z", "hostAddress", "203.0.113.7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ONLINE"))
                .andExpect(jsonPath("$.observedAddress").value("192.168.1.50"))
                .andExpect(jsonPath("$.heartbeatIntervalSeconds").value(15))
                .andExpect(jsonPath("$.staleTimeoutSeconds").value(60));

        Peer peer = peerRepository.findById(UUID.fromString(peerId)).orElseThrow();
        assertThat(peer.getStatus()).isEqualTo(PeerStatus.ONLINE);
        assertThat(peer.getLastHeartbeatAt()).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(peer.getHostAddress()).isEqualTo(InetAddress.getByName("192.168.1.50"));
        assertThat(output).doesNotContain(credential);
    }

    @Test
    void repeatedHeartbeatsAreIdempotentAndMoveForward() throws Exception {
        JsonNode registration = registration(aliceSession, "laptop");
        String peerId = registration.at("/peer/id").asText();
        String credential = registration.at("/agentCredential/credential").asText();

        mockMvc.perform(heartbeat(peerId, credential)).andExpect(status().isOk());
        Instant first = peerRepository.findById(UUID.fromString(peerId)).orElseThrow().getLastHeartbeatAt();
        mockMvc.perform(heartbeat(peerId, credential)).andExpect(status().isOk());
        mockMvc.perform(heartbeat(peerId, credential)).andExpect(status().isOk());

        Peer peer = peerRepository.findById(UUID.fromString(peerId)).orElseThrow();
        assertThat(peer.getStatus()).isEqualTo(PeerStatus.ONLINE);
        assertThat(peer.getLastHeartbeatAt()).isAfterOrEqualTo(first);
        mockMvc.perform(get("/api/peers/" + peerId).session(aliceSession))
                .andExpect(jsonPath("$.status").value("ONLINE"))
                .andExpect(jsonPath("$.hostAddress").value("127.0.0.1"));
    }

    @Test
    void heartbeatRejectsEveryoneButTheMatchingAgent() throws Exception {
        JsonNode laptop = registration(aliceSession, "laptop");
        JsonNode phone = registration(aliceSession, "phone");
        String laptopId = laptop.at("/peer/id").asText();
        String phoneCredential = phone.at("/agentCredential/credential").asText();

        // Anonymous, signed-in owner (session), invalid credential, another peer's credential.
        mockMvc.perform(post("/api/peers/" + laptopId + "/heartbeat"))
                .andExpect(status().isForbidden()); // no CSRF token: rejected before authentication
        mockMvc.perform(post("/api/peers/" + laptopId + "/heartbeat").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/peers/" + laptopId + "/heartbeat").session(aliceSession).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(heartbeat(laptopId, "plk_not-a-real-credential"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(heartbeat(laptopId, phoneCredential))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(peerRepository.findById(UUID.fromString(laptopId)).orElseThrow().getStatus())
                .isEqualTo(PeerStatus.OFFLINE);
    }

    @Test
    void invalidBearerCredentialNeverFallsBackToTheSession() throws Exception {
        mockMvc.perform(get("/api/peers/mine").session(aliceSession)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer plk_bogus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentCredentialGrantsHeartbeatsOnly() throws Exception {
        JsonNode laptop = registration(aliceSession, "laptop");
        String credential = laptop.at("/agentCredential/credential").asText();
        String bearer = "Bearer " + credential;

        mockMvc.perform(get("/api/peers/mine").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/peers").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("deviceName", "x", "listeningPort", 9090))))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokingOrRotatingCredentialStopsOldCredential() throws Exception {
        JsonNode laptop = registration(aliceSession, "laptop");
        String peerId = laptop.at("/peer/id").asText();
        String original = laptop.at("/agentCredential/credential").asText();
        mockMvc.perform(heartbeat(peerId, original)).andExpect(status().isOk());

        String rotated = mockMvc.perform(post("/api/peers/" + peerId + "/credential").session(aliceSession).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString().transform(this::readTree).get("credential").asText();
        assertThat(rotated).isNotEqualTo(original);
        mockMvc.perform(heartbeat(peerId, original)).andExpect(status().isUnauthorized());
        mockMvc.perform(heartbeat(peerId, rotated)).andExpect(status().isOk());

        mockMvc.perform(delete("/api/peers/" + peerId + "/credential").session(aliceSession))
                .andExpect(status().isForbidden()); // no CSRF token
        mockMvc.perform(delete("/api/peers/" + peerId + "/credential").session(aliceSession).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(heartbeat(peerId, rotated)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/peers/" + peerId).session(aliceSession))
                .andExpect(jsonPath("$.status").value("OFFLINE"))
                .andExpect(jsonPath("$.agentCredentialActive").value(false));
    }

    @Test
    void disabledOwnersAgentCannotSendHeartbeats() throws Exception {
        JsonNode laptop = registration(aliceSession, "laptop");
        User owner = userRepository.findByUsername(alice).orElseThrow();
        owner.disable();
        userRepository.saveAndFlush(owner);

        mockMvc.perform(heartbeat(laptop.at("/peer/id").asText(), laptop.at("/agentCredential/credential").asText()))
                .andExpect(status().isUnauthorized());
    }

    // --- Discovery ------------------------------------------------------------------

    @Test
    void discoveryListsOnlyRecentlySeenPeersOfOtherEnabledUsers() throws Exception {
        Instant now = Instant.now();
        User bob = createUser(uniqueUsername());
        User disabled = createUser(uniqueUsername());
        disabled.disable();
        userRepository.saveAndFlush(disabled);

        Peer online = peer(bob, "bob-online", PeerStatus.ONLINE, now.minusSeconds(5));
        Peer offline = peer(bob, "bob-offline", PeerStatus.OFFLINE, now.minusSeconds(5));
        Peer stale = peer(bob, "bob-stale", PeerStatus.ONLINE, now.minus(Duration.ofMinutes(5)));
        Peer disabledUsers = peer(disabled, "disabled-online", PeerStatus.ONLINE, now.minusSeconds(5));
        Peer alicesOwn = peer(userRepository.findByUsername(alice).orElseThrow(), "alice-online",
                PeerStatus.ONLINE, now.minusSeconds(5));

        String body = mockMvc.perform(get("/api/peers/online").session(aliceSession).param("size", "100"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode page = readTree(body);
        String ids = page.get("content").toString();

        assertThat(ids).contains(online.getId().toString())
                .doesNotContain(offline.getId().toString())
                .doesNotContain(stale.getId().toString())
                .doesNotContain(disabledUsers.getId().toString())
                .doesNotContain(alicesOwn.getId().toString());
        assertThat(page.get("size").asInt()).isEqualTo(100);
        // Discovery exposes no email, address or port.
        assertThat(body).doesNotContain("@example.com").doesNotContain("hostAddress")
                .doesNotContain("listeningPort").doesNotContain("10.1.2.3");

        // The owner still sees all of their own devices, online or not.
        mockMvc.perform(get("/api/peers/mine").session(aliceSession))
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.hasItem(alicesOwn.getId().toString())));
    }

    @Test
    void discoveryClampsPageSizeAndRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/peers/online").session(aliceSession).param("size", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
        mockMvc.perform(get("/api/peers/online")).andExpect(status().isUnauthorized());
    }

    // --- Helpers --------------------------------------------------------------------

    private ResultActions registerPeer(MockHttpSession session, Map<String, ?> body) throws Exception {
        return mockMvc.perform(post("/api/peers").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private JsonNode registration(MockHttpSession session, String deviceName) throws Exception {
        return readTree(registerPeer(session, Map.of("deviceName", deviceName, "listeningPort", 9090))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private String registeredPeerId(MockHttpSession session, String deviceName) throws Exception {
        return registration(session, deviceName).at("/peer/id").asText();
    }

    /** Agent requests carry only the bearer credential: no session cookie and no CSRF token. */
    private static MockHttpServletRequestBuilder heartbeat(String peerId, String credential) {
        return post("/api/peers/" + peerId + "/heartbeat").header(HttpHeaders.AUTHORIZATION, "Bearer " + credential);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private Peer peer(User owner, String deviceName, PeerStatus status, Instant lastHeartbeat) throws Exception {
        Peer peer = new Peer(owner, deviceName + "-" + UUID.randomUUID().toString().substring(0, 4),
                InetAddress.getByName("10.1.2.3"), 9090);
        peer.recordHeartbeat(InetAddress.getByName("10.1.2.3"), lastHeartbeat);
        if (status == PeerStatus.OFFLINE) {
            peer.markOffline();
        }
        return peerRepository.saveAndFlush(peer);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
