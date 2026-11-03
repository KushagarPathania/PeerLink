package com.peerlink.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.peerlink.server.entity.FileTransfer;
import com.peerlink.server.entity.TransferErrorCode;
import com.peerlink.server.entity.TransferEvent;
import com.peerlink.server.entity.TransferStatus;
import com.peerlink.server.repository.FileTransferRepository;
import com.peerlink.server.repository.TransferEventRepository;
import com.peerlink.server.security.SecretTokens;
import com.peerlink.server.support.WebIntegrationTestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sharing and transfer coordination end-to-end through the real security filters and PostgreSQL:
 * alice shares a file from her laptop, bob asks for it on his desktop, and the two agents report.
 */
class FileTransferApiIntegrationTests extends WebIntegrationTestSupport {

    private static final String CHECKSUM = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @Autowired
    private FileTransferRepository transferRepository;

    @Autowired
    private TransferEventRepository eventRepository;

    @Autowired
    private TransactionTemplate transactions;

    private MockHttpSession aliceSession;
    private MockHttpSession bobSession;
    private String bobUsername;
    private Agent laptop;   // alice's, holds the file
    private Agent desktop;  // bob's, receives it
    private String fileId;

    private record Agent(String peerId, String credential, String certificate) {
        Agent(String peerId, String credential) {
            this(peerId, credential, SecretTokens.sha256Hex("cert-" + peerId));
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        String alice = uniqueUsername();
        createUser(alice);
        aliceSession = apiLogin(alice);
        bobUsername = uniqueUsername();
        createUser(bobUsername);
        bobSession = apiLogin(bobUsername);
        laptop = registerOnlinePeer(aliceSession, "alice-laptop");
        desktop = registerOnlinePeer(bobSession, "bob-desktop");
        fileId = registerFile(laptop, "report.pdf", 1000).get("id").asText();
    }

    // --- Sharing ----------------------------------------------------------------------

    @Test
    void agentRegistersFilesIdempotentlyAndOnlyForItsOwnPeer() throws Exception {
        assertThat(registerFile(laptop, "report.pdf", 1000).get("id").asText()).isEqualTo(fileId);
        agentPost(laptop, "/api/peers/" + laptop.peerId() + "/files",
                Map.of("fileName", "../etc/passwd", "fileSizeBytes", 1, "sha256Checksum", CHECKSUM, "chunkSizeBytes", 65536))
                .andExpect(status().isBadRequest());
        agentPost(desktop, "/api/peers/" + laptop.peerId() + "/files",
                Map.of("fileName", "x.txt", "fileSizeBytes", 1, "sha256Checksum", CHECKSUM, "chunkSizeBytes", 65536))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/peers/" + laptop.peerId() + "/files").session(aliceSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("fileName", "x.txt", "fileSizeBytes", 1, "sha256Checksum", CHECKSUM,
                                "chunkSizeBytes", 65536))))
                .andExpect(status().isForbidden());
    }

    @Test
    void chunkSizesOutsideTheTransferProtocolLimitsCannotBeRegistered() throws Exception {
        for (int chunkSize : new int[] {8, 16, 4095, 65537, 16 * 1024 * 1024}) {
            agentPost(laptop, "/api/peers/" + laptop.peerId() + "/files", Map.of("fileName", "small-" + chunkSize + ".bin",
                    "fileSizeBytes", 12 * 1024 * 1024, "sha256Checksum", CHECKSUM, "chunkSizeBytes", chunkSize))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("chunkSizeBytes"));
        }
        for (int chunkSize : new int[] {4096, 65536}) {
            agentPost(laptop, "/api/peers/" + laptop.peerId() + "/files", Map.of("fileName", "edge-" + chunkSize + ".bin",
                    "fileSizeBytes", 12 * 1024 * 1024, "sha256Checksum", CHECKSUM, "chunkSizeBytes", chunkSize))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.chunkSizeBytes").value(chunkSize));
        }
    }

    @Test
    void onlyTheOwnerCanGrantListAndRevokeAccess() throws Exception {
        mockMvc.perform(get("/api/files/mine").session(aliceSession))
                .andExpect(jsonPath("$.content[0].id").value(fileId))
                .andExpect(jsonPath("$.content[0].sourceDeviceName").value("alice-laptop"))
                .andExpect(jsonPath("$.content[0].sourcePeerOnline").value(true));
        mockMvc.perform(get("/api/files/shared-with-me").session(bobSession))
                .andExpect(jsonPath("$.totalElements").value(0));

        grant(bobSession, fileId, bobUsername).andExpect(status().isNotFound()); // bob does not own it
        String permissionId = readTree(grant(aliceSession, fileId, bobUsername).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/files/shared-with-me").session(bobSession))
                .andExpect(jsonPath("$.content[0].id").value(fileId));
        mockMvc.perform(get("/api/files/" + fileId + "/permissions").session(bobSession)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/files/" + fileId + "/permissions").session(aliceSession))
                .andExpect(jsonPath("$[0].permittedUsername").value(bobUsername));

        mockMvc.perform(delete("/api/files/" + fileId + "/permissions/" + permissionId).session(aliceSession).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/files/shared-with-me").session(bobSession))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void grantingRequiresAnExistingOtherUserAndCsrf() throws Exception {
        grant(aliceSession, fileId, "no-such-user").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("username"));
        mockMvc.perform(post("/api/files/" + fileId + "/permissions").session(aliceSession)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("username", bobUsername))))
                .andExpect(status().isForbidden());
    }

    // --- Requesting transfers ---------------------------------------------------------

    @Test
    void transferRequiresPermissionOwnReceiverAndOnlineSender() throws Exception {
        // Not shared with bob yet: indistinguishable from a missing file.
        requestTransfer(bobSession, fileId, desktop.peerId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
        grant(aliceSession, fileId, bobUsername).andExpect(status().isCreated());

        // Bob cannot send it to someone else's device.
        requestTransfer(bobSession, fileId, laptop.peerId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PEER_NOT_FOUND"));
        requestTransfer(bobSession, fileId, desktop.peerId()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.senderDeviceName").value("alice-laptop"))
                .andExpect(jsonPath("$.receiverDeviceName").value("bob-desktop"));
        requestTransfer(bobSession, fileId, desktop.peerId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_POSSIBLE"));
        mockMvc.perform(post("/api/transfers").session(bobSession).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("sharedFileId", fileId, "receiverPeerId", desktop.peerId()))))
                .andExpect(status().isForbidden()); // no CSRF token
    }

    // --- Agent flow -------------------------------------------------------------------

    @Test
    void fullAgentFlowRecordsEveryStepWithTheReportingPeer() throws Exception {
        String transferId = requestedTransfer();

        JsonNode pending = readTree(agentGet(desktop, "/api/peers/" + desktop.peerId() + "/transfers/next")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(pending.get("transferId").asText()).isEqualTo(transferId);
        assertThat(pending.get("sha256Checksum").asText()).isEqualTo(CHECKSUM);
        assertThat(pending.get("fileSizeBytes").asLong()).isEqualTo(1000);
        agentGet(laptop, "/api/peers/" + laptop.peerId() + "/transfers/next").andExpect(status().isNoContent());

        JsonNode ticket = ticketFor(desktop, laptop, transferId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferId").value(transferId))
                .andReturn().getResponse().getContentAsString().transform(this::readTree);
        // While the ticket is out, the transfer is not handed out again.
        agentGet(desktop, "/api/peers/" + desktop.peerId() + "/transfers/next").andExpect(status().isNoContent());

        agentPost(laptop, "/api/peers/" + laptop.peerId() + "/connections/redeem", Map.of("ticket", ticket.get("ticket").asText(),
                        "presentedCertificateSha256", desktop.certificate()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value(transferId))
                .andExpect(jsonPath("$.sharedFileId").value(fileId))
                .andExpect(jsonPath("$.fileName").value("report.pdf"))
                .andExpect(jsonPath("$.fileSizeBytes").value(1000))
                .andExpect(jsonPath("$.sha256Checksum").value(CHECKSUM))
                .andExpect(jsonPath("$.initiatorCertificateSha256").value(desktop.certificate()));
        assertThat(statusOf(transferId)).isEqualTo(TransferStatus.AUTHORIZED);

        progress(desktop, transferId, 0, 0).andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.authorized").value(true));
        progress(desktop, transferId, 512, 0).andExpect(status().isOk());
        progress(desktop, transferId, 100, 0).andExpect(status().isBadRequest()); // backwards
        progress(laptop, transferId, 600, 0).andExpect(status().isForbidden());   // only the receiver reports progress

        complete(desktop, transferId, 1000, CHECKSUM).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        // A repeated report (the agent retries when it misses the answer) is acknowledged, not recorded again.
        complete(desktop, transferId, 1000, CHECKSUM).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        List<TransferEvent> events = transactions.execute(status -> {
            FileTransfer transfer = transferRepository.findById(UUID.fromString(transferId)).orElseThrow();
            List<TransferEvent> loaded = eventRepository.findByTransferOrderBySequenceNumberAsc(transfer);
            loaded.forEach(event -> {
                if (event.getReportedByPeer() != null) {
                    event.getReportedByPeer().getId();
                }
            });
            return loaded;
        });
        assertThat(events).extracting(TransferEvent::getToStatus).containsExactly(TransferStatus.REQUESTED,
                TransferStatus.AUTHORIZED, TransferStatus.IN_PROGRESS, TransferStatus.COMPLETED);
        assertThat(events.get(1).getReportedByPeer().getId().toString()).isEqualTo(laptop.peerId());
        assertThat(events.get(3).getReportedByPeer().getId().toString()).isEqualTo(desktop.peerId());
    }

    @Test
    void completionWithWrongChecksumOrSizeFailsTheTransfer() throws Exception {
        String transferId = acceptedTransfer();

        complete(desktop, transferId, 1000, "f".repeat(64)).andExpect(status().isBadRequest());

        assertThat(statusOf(transferId)).isEqualTo(TransferStatus.FAILED);
        assertThat(transferRepository.findById(UUID.fromString(transferId)).orElseThrow().getErrorCode())
                .isEqualTo(TransferErrorCode.CHECKSUM_MISMATCH);
    }

    @Test
    void repeatedCompletionIsIdempotentButConflictingReportsAreRefused() throws Exception {
        String transferId = acceptedTransfer();
        complete(desktop, transferId, 1000, CHECKSUM).andExpect(status().isOk());

        complete(desktop, transferId, 1000, CHECKSUM).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        complete(desktop, transferId, 1000, "f".repeat(64)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_STATE_CONFLICT"));
        complete(desktop, transferId, 999, CHECKSUM).andExpect(status().isConflict());
        complete(laptop, transferId, 1000, CHECKSUM).andExpect(status().isForbidden()); // only the receiver

        assertThat(statusOf(transferId)).isEqualTo(TransferStatus.COMPLETED);
        assertThat(eventStatuses(transferId)).containsExactly(TransferStatus.REQUESTED, TransferStatus.AUTHORIZED,
                TransferStatus.IN_PROGRESS, TransferStatus.COMPLETED);
    }

    @Test
    void completionOfATransferThatAlreadyFailedIsAConflictAndChangesNothing() throws Exception {
        String transferId = acceptedTransfer();
        agentPost(laptop, "/api/peers/" + laptop.peerId() + "/transfers/" + transferId + "/fail",
                Map.of("errorCode", "CONNECTION_LOST")).andExpect(status().isOk());

        complete(desktop, transferId, 1000, CHECKSUM).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_STATE_CONFLICT"));

        assertThat(statusOf(transferId)).isEqualTo(TransferStatus.FAILED);
        assertThat(transferRepository.findById(UUID.fromString(transferId)).orElseThrow().getErrorCode())
                .isEqualTo(TransferErrorCode.CONNECTION_LOST);
    }

    @Test
    void completionAfterAccessWasRevokedFailsTheTransferAndIsCommitted() throws Exception {
        String permissionId = readTree(grant(aliceSession, fileId, bobUsername).andReturn().getResponse()
                .getContentAsString()).get("id").asText();
        String transferId = acceptedTransfer();
        progress(desktop, transferId, 1000, 1).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mockMvc.perform(delete("/api/files/" + fileId + "/permissions/" + permissionId).session(aliceSession).with(csrf()))
                .andExpect(status().isNoContent());

        complete(desktop, transferId, 1000, CHECKSUM).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_STATE_CONFLICT"));

        assertThat(statusOf(transferId)).isEqualTo(TransferStatus.FAILED);
        assertThat(transferRepository.findById(UUID.fromString(transferId)).orElseThrow().getErrorCode())
                .isEqualTo(TransferErrorCode.PERMISSION_DENIED);
        // Retrying cannot turn it into a success.
        complete(desktop, transferId, 1000, CHECKSUM).andExpect(status().isConflict());
    }

    @Test
    void concurrentCompletionReportsRecordExactlyOneCompletion() throws Exception {
        String transferId = acceptedTransfer();
        int attempts = 4;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(attempts);
        List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return complete(desktop, transferId, 1000, CHECKSUM).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new java.util.ArrayList<>();
        for (java.util.concurrent.Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();

        // Each answer is either the completion or a retryable concurrent-modification conflict, never a failure.
        assertThat(statuses).contains(200).allMatch(status -> status == 200 || status == 409);
        complete(desktop, transferId, 1000, CHECKSUM).andExpect(status().isOk());
        assertThat(eventStatuses(transferId)).containsExactly(TransferStatus.REQUESTED, TransferStatus.AUTHORIZED,
                TransferStatus.IN_PROGRESS, TransferStatus.COMPLETED);
    }

    @Test
    void ticketsForTransfersAreScopedToTheirPeersAndState() throws Exception {
        String transferId = requestedTransfer();

        // The sender cannot fetch its own outgoing transfer, and a stranger's peer cannot either.
        ticketFor(laptop, desktop, transferId).andExpect(status().isNotFound());
        Agent charlie = registerOnlinePeer(apiLogin(createdUsername()), "charlie-box");
        ticketFor(charlie, laptop, transferId).andExpect(status().isNotFound());

        ticketFor(desktop, laptop, transferId).andExpect(status().isCreated());
        ticketFor(desktop, laptop, transferId).andExpect(status().isConflict()); // one outstanding ticket at a time
    }

    @Test
    void revokingAccessStopsATransferInFlight() throws Exception {
        String permissionId = readTree(grant(aliceSession, fileId, bobUsername).andReturn().getResponse()
                .getContentAsString()).get("id").asText();
        String transferId = acceptedTransfer();
        progress(desktop, transferId, 100, 1).andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        mockMvc.perform(delete("/api/files/" + fileId + "/permissions/" + permissionId).session(aliceSession).with(csrf()))
                .andExpect(status().isNoContent());

        agentGet(laptop, "/api/peers/" + laptop.peerId() + "/transfers/" + transferId)
                .andExpect(jsonPath("$.authorized").value(false));
        progress(desktop, transferId, 200, 1)
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.authorized").value(false));
        assertThat(transferRepository.findById(UUID.fromString(transferId)).orElseThrow().getErrorCode())
                .isEqualTo(TransferErrorCode.PERMISSION_DENIED);
    }

    @Test
    void ticketForAnUnauthorizedTransferIsRefused() throws Exception {
        String transferId = requestedTransfer();
        mockMvc.perform(delete("/api/files/" + fileId).session(aliceSession).with(csrf())).andExpect(status().isNoContent());

        ticketFor(desktop, laptop, transferId).andExpect(status().isNotFound());
    }

    @Test
    void eitherPeerMayFailOrRejectButOnlyOnce() throws Exception {
        String rejected = acceptedTransfer();
        agentPost(desktop, "/api/peers/" + desktop.peerId() + "/transfers/" + rejected + "/reject",
                Map.of("errorCode", "FILE_TOO_LARGE")).andExpect(jsonPath("$.status").value("REJECTED"));
        agentPost(laptop, "/api/peers/" + laptop.peerId() + "/transfers/" + rejected + "/fail",
                Map.of("errorCode", "FILE_CHANGED")).andExpect(status().isConflict());

        String failed = acceptedTransfer();
        agentPost(laptop, "/api/peers/" + laptop.peerId() + "/transfers/" + failed + "/fail",
                Map.of("errorCode", "FILE_CHANGED")).andExpect(jsonPath("$.status").value("FAILED"));
        agentPost(desktop, "/api/peers/" + desktop.peerId() + "/transfers/" + failed + "/fail",
                Map.of("errorCode", "NOT_A_CODE")).andExpect(status().isBadRequest());
    }

    // --- Users' view ------------------------------------------------------------------

    @Test
    void usersSeeAndCancelOnlyTheirOwnTransfers() throws Exception {
        String transferId = requestedTransfer();
        MockHttpSession stranger = apiLogin(createdUsername());

        mockMvc.perform(get("/api/transfers/" + transferId).session(bobSession)).andExpect(status().isOk());
        mockMvc.perform(get("/api/transfers/" + transferId).session(aliceSession)).andExpect(status().isOk());
        mockMvc.perform(get("/api/transfers/" + transferId).session(stranger)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/transfers/" + transferId + "/cancel").session(stranger).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/transfers").session(bobSession))
                .andExpect(jsonPath("$.content[0].id").value(transferId));
        mockMvc.perform(get("/api/transfers").session(stranger)).andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(post("/api/transfers/" + transferId + "/cancel").session(bobSession).with(csrf()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/transfers/" + transferId + "/cancel").session(aliceSession).with(csrf()))
                .andExpect(status().isConflict());
        // An agent asking about it learns it is over.
        agentGet(laptop, "/api/peers/" + laptop.peerId() + "/transfers/" + transferId)
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.authorized").value(false));
    }

    @Test
    void agentsCannotSeeTransfersOfOtherPeers() throws Exception {
        String transferId = requestedTransfer();
        Agent charlie = registerOnlinePeer(apiLogin(createdUsername()), "charlie-box");

        agentGet(charlie, "/api/peers/" + charlie.peerId() + "/transfers/" + transferId).andExpect(status().isNotFound());
        agentGet(charlie, "/api/peers/" + laptop.peerId() + "/transfers/" + transferId).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/peers/" + laptop.peerId() + "/transfers/" + transferId).session(aliceSession))
                .andExpect(status().isForbidden()); // browser sessions never act as agents
    }

    // --- Helpers ----------------------------------------------------------------------

    private String requestedTransfer() throws Exception {
        grant(aliceSession, fileId, bobUsername);
        return readTree(requestTransfer(bobSession, fileId, desktop.peerId()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    /** A transfer whose sender agent has redeemed its ticket (AUTHORIZED). */
    private String acceptedTransfer() throws Exception {
        String transferId = requestedTransfer();
        String ticket = readTree(ticketFor(desktop, laptop, transferId).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("ticket").asText();
        agentPost(laptop, "/api/peers/" + laptop.peerId() + "/connections/redeem",
                Map.of("ticket", ticket, "presentedCertificateSha256", desktop.certificate()))
                .andExpect(status().isOk());
        return transferId;
    }

    private TransferStatus statusOf(String transferId) {
        return transferRepository.findById(UUID.fromString(transferId)).orElseThrow().getStatus();
    }

    private Agent registerOnlinePeer(MockHttpSession session, String deviceName) throws Exception {
        JsonNode registration = readTree(mockMvc.perform(post("/api/peers").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("deviceName", deviceName, "listeningPort", 9090))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        Agent agent = new Agent(registration.at("/peer/id").asText(), registration.at("/agentCredential/credential").asText());
        agentPost(agent, "/api/peers/" + agent.peerId() + "/heartbeat",
                Map.of("listeningPort", 9090, "tlsCertificateSha256", agent.certificate())).andExpect(status().isOk());
        return agent;
    }

    private List<TransferStatus> eventStatuses(String transferId) {
        return transactions.execute(status -> eventRepository.findByTransferOrderBySequenceNumberAsc(
                        transferRepository.findById(UUID.fromString(transferId)).orElseThrow())
                .stream().map(TransferEvent::getToStatus).toList());
    }

    private JsonNode registerFile(Agent agent, String name, long size) throws Exception {
        return readTree(agentPost(agent, "/api/peers/" + agent.peerId() + "/files",
                Map.of("fileName", name, "fileSizeBytes", size, "sha256Checksum", CHECKSUM, "chunkSizeBytes", 4096))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions grant(MockHttpSession session, String file, String username) throws Exception {
        return mockMvc.perform(post("/api/files/" + file + "/permissions").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("username", username))));
    }

    private ResultActions requestTransfer(MockHttpSession session, String file, String receiverPeerId) throws Exception {
        return mockMvc.perform(post("/api/transfers").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("sharedFileId", file, "receiverPeerId", receiverPeerId))));
    }

    private ResultActions ticketFor(Agent source, Agent target, String transferId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("targetPeerId", target.peerId());
        body.put("transferId", transferId);
        body.put("initiatorCertificateSha256", source.certificate());
        return agentPost(source, "/api/peers/" + source.peerId() + "/connections", body);
    }

    private ResultActions progress(Agent agent, String transferId, long bytes, int chunks) throws Exception {
        return agentPost(agent, "/api/peers/" + agent.peerId() + "/transfers/" + transferId + "/progress",
                Map.of("bytesTransferred", bytes, "chunksCompleted", chunks));
    }

    private ResultActions complete(Agent agent, String transferId, long bytes, String sha256) throws Exception {
        return agentPost(agent, "/api/peers/" + agent.peerId() + "/transfers/" + transferId + "/complete",
                Map.of("bytesTransferred", bytes, "sha256Checksum", sha256));
    }

    private ResultActions agentPost(Agent agent, String path, Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.credential())
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions agentGet(Agent agent, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.credential()));
    }

    private String createdUsername() {
        String username = uniqueUsername();
        createUser(username);
        return username;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
