package com.peerlink.agent.transfer;

import com.peerlink.agent.config.AgentCredential;
import com.peerlink.agent.networking.CoordinationServerClient;
import com.peerlink.agent.networking.CoordinationServerClient.JsonResponse;
import com.peerlink.agent.protocol.TransferError;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@link TransferCoordinator} over the coordination server's agent endpoints. */
public final class CoordinationTransferCoordinator implements TransferCoordinator {

    private static final Logger LOG = System.getLogger(CoordinationTransferCoordinator.class.getName());

    private final CoordinationServerClient client;
    private final UUID peerId;
    private final AgentCredential credential;

    public CoordinationTransferCoordinator(CoordinationServerClient client, UUID peerId, AgentCredential credential) {
        this.client = Objects.requireNonNull(client);
        this.peerId = Objects.requireNonNull(peerId);
        this.credential = Objects.requireNonNull(credential);
    }

    @Override
    public UUID registerSharedFile(String fileName, long sizeBytes, String sha256, String contentType, int chunkSizeBytes)
            throws CoordinationException {
        Map<String, Object> body = new HashMap<>();
        body.put("fileName", fileName);
        body.put("fileSizeBytes", sizeBytes);
        body.put("sha256Checksum", sha256);
        body.put("chunkSizeBytes", chunkSizeBytes);
        if (contentType != null) {
            body.put("contentType", contentType);
        }
        JsonResponse response = post("/files", body);
        return uuid(response, "id");
    }

    @Override
    public Optional<PendingTransfer> nextTransfer() throws CoordinationException {
        JsonResponse response = get("/transfers/next");
        if (response.status() == 204) {
            return Optional.empty();
        }
        Map<String, String> json = response.body();
        try {
            return Optional.of(new PendingTransfer(UUID.fromString(json.get("transferId")),
                    UUID.fromString(json.get("sharedFileId")), UUID.fromString(json.get("senderPeerId")),
                    Objects.requireNonNull(json.get("fileName")), Long.parseLong(json.get("fileSizeBytes")),
                    Objects.requireNonNull(json.get("sha256Checksum")), Integer.parseInt(json.get("chunkSizeBytes")),
                    Integer.parseInt(json.get("totalChunks"))));
        } catch (RuntimeException e) {
            throw new CoordinationException(response.status(), "Unexpected pending-transfer response");
        }
    }

    @Override
    public TransferState status(UUID transferId) throws CoordinationException {
        return state(get("/transfers/" + transferId));
    }

    @Override
    public TransferState reportProgress(UUID transferId, long bytesTransferred, int chunksCompleted)
            throws CoordinationException {
        return state(post("/transfers/" + transferId + "/progress",
                Map.of("bytesTransferred", bytesTransferred, "chunksCompleted", chunksCompleted)));
    }

    @Override
    public ReportResult reportCompleted(UUID transferId, long bytesTransferred, String sha256) {
        String path = "/transfers/" + transferId + "/complete";
        try {
            JsonResponse response = client.postJson(base() + path, credential,
                    Map.of("bytesTransferred", bytesTransferred, "sha256Checksum", sha256));
            ReportResult result = classifyCompletion(response);
            if (result != ReportResult.ACCEPTED) {
                LOG.log(Level.WARNING, "The server did not accept the completion of transfer {0} (HTTP {1}{2})",
                        transferId, response.status(), response.body().containsKey("code")
                                ? ", " + response.body().get("code").replaceAll("[^A-Z_]", "") : "");
            }
            return result;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not report {0} to the server: {1}", path, e.getClass().getSimpleName());
            return ReportResult.UNDELIVERED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ReportResult.UNDELIVERED;
        }
    }

    /**
     * Success, or a server-side state that retrying cannot change (the transfer ended differently,
     * the report does not match, this peer may not report it), versus a temporary condition.
     */
    static ReportResult classifyCompletion(JsonResponse response) {
        int status = response.status();
        if (response.ok()) {
            return ReportResult.ACCEPTED;
        }
        if (status == 409 && "CONCURRENT_MODIFICATION".equals(response.body().get("code"))) {
            return ReportResult.UNDELIVERED; // a concurrent update; the retry gets a definite answer
        }
        if (status == 408 || status == 429 || status >= 500) {
            return ReportResult.UNDELIVERED;
        }
        return ReportResult.REFUSED;
    }

    @Override
    public boolean reportFailed(UUID transferId, TransferError error) {
        return error.reportable() && report("/transfers/" + transferId + "/fail", Map.of("errorCode", error.name()));
    }

    @Override
    public boolean reportRejected(UUID transferId, TransferError error) {
        return error.reportable() && report("/transfers/" + transferId + "/reject", Map.of("errorCode", error.name()));
    }

    private boolean report(String path, Map<String, ?> body) {
        try {
            JsonResponse response = client.postJson(base() + path, credential, body);
            if (!response.ok()) {
                LOG.log(Level.WARNING, "The server did not accept the report {0} (HTTP {1})", path, response.status());
            }
            return response.ok();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not report {0} to the server: {1}", path, e.getClass().getSimpleName());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private JsonResponse post(String path, Map<String, ?> body) throws CoordinationException {
        try {
            return requireOk(client.postJson(base() + path, credential, body));
        } catch (IOException e) {
            throw new CoordinationException(0, "The coordination server is unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CoordinationException(0, "Interrupted");
        }
    }

    private JsonResponse get(String path) throws CoordinationException {
        try {
            return requireOk(client.getJson(base() + path, credential));
        } catch (IOException e) {
            throw new CoordinationException(0, "The coordination server is unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CoordinationException(0, "Interrupted");
        }
    }

    private static JsonResponse requireOk(JsonResponse response) throws CoordinationException {
        if (!response.ok()) {
            throw new CoordinationException(response.status(), "The coordination server answered HTTP " + response.status());
        }
        return response;
    }

    private static TransferState state(JsonResponse response) throws CoordinationException {
        String status = response.body().get("status");
        if (status == null) {
            throw new CoordinationException(response.status(), "Unexpected transfer status response");
        }
        return new TransferState(status, Boolean.parseBoolean(response.body().get("authorized")));
    }

    private static UUID uuid(JsonResponse response, String field) throws CoordinationException {
        try {
            return UUID.fromString(response.body().get(field));
        } catch (RuntimeException e) {
            throw new CoordinationException(response.status(), "Unexpected response from the server");
        }
    }

    private String base() {
        return "/api/peers/" + peerId;
    }
}
