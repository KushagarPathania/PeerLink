package com.peerlink.agent.networking;

import com.peerlink.agent.config.AgentCredential;
import com.peerlink.agent.presence.HeartbeatResult;
import com.peerlink.agent.presence.PresenceClient;
import com.peerlink.agent.protocol.Handshake;
import com.peerlink.agent.security.CertificateFingerprints;
import com.peerlink.agent.security.ConnectionOutcome;
import com.peerlink.agent.security.IssuedTicket;
import com.peerlink.agent.security.TicketException;
import com.peerlink.agent.security.TicketException.Reason;
import com.peerlink.agent.security.TransferGrant;
import com.peerlink.agent.security.VerifiedTicket;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * HTTP client for the coordination server's REST API. The agent only exchanges control
 * messages with the server; file contents always travel peer-to-peer over sockets.
 * Network failures are reported as results, never thrown.
 */
public class CoordinationServerClient implements PresenceClient {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private static final Pattern IP_LITERAL = Pattern.compile("[0-9a-fA-F.:]{2,45}");

    public enum ServerStatus {
        HEALTHY,
        UNHEALTHY,
        UNREACHABLE
    }

    private final URI serverUrl;
    private final Duration timeout;
    private final HttpClient httpClient;

    public CoordinationServerClient(URI serverUrl) {
        this(serverUrl, DEFAULT_TIMEOUT);
    }

    /** @param timeout applies separately to establishing the connection and to waiting for the response */
    public CoordinationServerClient(URI serverUrl, Duration timeout) {
        this.serverUrl = serverUrl;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public ServerStatus checkHealth() {
        HttpRequest request = HttpRequest.newBuilder(endpoint("/api/health"))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200 ? ServerStatus.HEALTHY : ServerStatus.UNHEALTHY;
        } catch (IOException e) {
            return ServerStatus.UNREACHABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ServerStatus.UNREACHABLE;
        }
    }

    @Override
    public HeartbeatResult sendHeartbeat(UUID peerId, AgentCredential credential, Integer listeningPort,
                                         String tlsCertificateSha256) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        if (listeningPort != null) {
            fields.put("listeningPort", listeningPort);
        }
        if (tlsCertificateSha256 != null) {
            fields.put("tlsCertificateSha256", tlsCertificateSha256);
        }
        String body = FlatJson.writeObject(fields);
        try {
            HttpResponse<String> response = post("/api/peers/" + peerId + "/heartbeat", credential, body);
            return HeartbeatResult.fromStatus(response.statusCode(), observedAddress(response));
        } catch (IOException e) {
            return HeartbeatResult.unreachable(); // includes HttpTimeoutException
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HeartbeatResult.unreachable();
        }
    }

    /**
     * Asks for a single-use ticket to connect from {@code sourcePeerId} to {@code targetPeerId},
     * optionally to fetch {@code transferId}, presenting the TLS certificate with
     * {@code initiatorCertificateSha256}.
     */
    public IssuedTicket requestConnectionTicket(UUID sourcePeerId, AgentCredential credential, UUID targetPeerId,
                                                UUID transferId, String initiatorCertificateSha256)
            throws TicketException {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("targetPeerId", targetPeerId.toString());
        if (transferId != null) {
            fields.put("transferId", transferId.toString());
        }
        fields.put("initiatorCertificateSha256", initiatorCertificateSha256);
        HttpResponse<String> response = postForTicket("/api/peers/" + sourcePeerId + "/connections", credential,
                FlatJson.writeObject(fields));
        return switch (response.statusCode()) {
            case 201 -> parseIssuedTicket(response.body(), targetPeerId, transferId);
            case 400, 404, 409 -> throw new TicketException(Reason.TARGET_UNAVAILABLE,
                    "The target peer does not exist, is offline or cannot be contacted (HTTP "
                            + response.statusCode() + ")");
            case 401, 403 -> throw new TicketException(Reason.CREDENTIAL_REJECTED,
                    "The server rejected this agent's credential (HTTP " + response.statusCode() + ")");
            default -> throw new TicketException(Reason.SERVER_UNAVAILABLE,
                    "The coordination server answered HTTP " + response.statusCode());
        };
    }

    /** Redeems a ticket that a connecting peer presented to {@code selfPeerId}. */
    public VerifiedTicket redeemConnectionTicket(UUID selfPeerId, AgentCredential credential, String ticket,
                                                 String presentedCertificateSha256) throws TicketException {
        if (!Handshake.TICKET.matcher(ticket).matches()) {
            throw new TicketException(Reason.INVALID_TICKET, "Malformed ticket");
        }
        HttpResponse<String> response = postForTicket("/api/peers/" + selfPeerId + "/connections/redeem", credential,
                FlatJson.writeObject(Map.of("ticket", ticket, "presentedCertificateSha256", presentedCertificateSha256)));
        return switch (response.statusCode()) {
            case 200 -> parseVerifiedTicket(response.body());
            case 400, 403 -> throw new TicketException(Reason.INVALID_TICKET,
                    "The server did not accept the presented ticket (HTTP " + response.statusCode() + ")");
            case 401 -> throw new TicketException(Reason.CREDENTIAL_REJECTED, "The server rejected this agent's credential");
            default -> throw new TicketException(Reason.SERVER_UNAVAILABLE,
                    "The coordination server answered HTTP " + response.statusCode());
        };
    }

    /** @return whether the server recorded the outcome */
    public boolean reportConnectionOutcome(UUID sourcePeerId, AgentCredential credential, UUID ticketId,
                                           ConnectionOutcome outcome) {
        try {
            HttpResponse<String> response = post("/api/peers/" + sourcePeerId + "/connections/" + ticketId + "/outcome",
                    credential, FlatJson.writeObject(Map.of("outcome", outcome.name())));
            return response.statusCode() == 204;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A server response: status and, for successful responses with a flat JSON body, its fields. */
    public record JsonResponse(int status, Map<String, String> body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** Generic authenticated JSON POST for agent endpoints; network errors propagate as IOException. */
    public JsonResponse postJson(String path, AgentCredential credential, Map<String, ?> fields)
            throws IOException, InterruptedException {
        return toJsonResponse(post(path, credential, FlatJson.writeObject(fields)));
    }

    public JsonResponse getJson(String path, AgentCredential credential) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint(path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + credential.value())
                .GET()
                .build();
        return toJsonResponse(httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    private static JsonResponse toJsonResponse(HttpResponse<String> response) throws IOException {
        int status = response.statusCode();
        String body = response.body();
        if (body == null || body.isBlank()) {
            return new JsonResponse(status, Map.of());
        }
        if (status < 200 || status >= 300) {
            // Error bodies are only consulted for their "code"; one that is not flat JSON is ignored.
            try {
                return new JsonResponse(status, FlatJson.parseObject(body));
            } catch (IllegalArgumentException e) {
                return new JsonResponse(status, Map.of());
            }
        }
        try {
            return new JsonResponse(status, FlatJson.parseObject(body));
        } catch (IllegalArgumentException e) {
            throw new IOException("Unexpected response from the coordination server", e);
        }
    }

    private HttpResponse<String> post(String path, AgentCredential credential, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint(path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + credential.value())
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForTicket(String path, AgentCredential credential, String jsonBody)
            throws TicketException {
        try {
            return post(path, credential, jsonBody);
        } catch (IOException e) {
            throw new TicketException(Reason.SERVER_UNAVAILABLE,
                    "The coordination server is unreachable (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TicketException(Reason.SERVER_UNAVAILABLE, "Interrupted");
        }
    }

    private static String observedAddress(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            return null;
        }
        try {
            String address = FlatJson.parseObject(response.body()).get("observedAddress");
            return address != null && IP_LITERAL.matcher(address).matches() ? address : null;
        } catch (IllegalArgumentException e) {
            return null; // the address is informational only
        }
    }

    private static IssuedTicket parseIssuedTicket(String body, UUID requestedTarget, UUID requestedTransfer)
            throws TicketException {
        try {
            Map<String, String> json = FlatJson.parseObject(body);
            UUID targetPeerId = UUID.fromString(required(json, "targetPeerId"));
            String ticket = required(json, "ticket");
            String proof = required(json, "responderProof");
            String host = required(json, "targetHost");
            String certificate = required(json, "targetCertificateSha256");
            int port = Integer.parseInt(required(json, "targetPort"));
            UUID transferId = json.get("transferId") == null ? null : UUID.fromString(json.get("transferId"));
            if (!targetPeerId.equals(requestedTarget) || !Handshake.TICKET.matcher(ticket).matches()
                    || !Handshake.PROOF.matcher(proof).matches() || port < 1 || port > 65535
                    || !CertificateFingerprints.FORMAT.matcher(certificate).matches()
                    || !java.util.Objects.equals(transferId, requestedTransfer)) {
                throw new IllegalArgumentException("Unexpected ticket contents");
            }
            return new IssuedTicket(UUID.fromString(required(json, "ticketId")), ticket, targetPeerId,
                    ipLiteral(host), port, Instant.parse(required(json, "expiresAt")), proof, certificate, transferId);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new TicketException(Reason.INVALID_RESPONSE, "Unexpected ticket response from the server");
        }
    }

    private static VerifiedTicket parseVerifiedTicket(String body) throws TicketException {
        try {
            Map<String, String> json = FlatJson.parseObject(body);
            String proof = required(json, "responderProof");
            String certificate = required(json, "initiatorCertificateSha256");
            if (!Handshake.PROOF.matcher(proof).matches() || !CertificateFingerprints.FORMAT.matcher(certificate).matches()) {
                throw new IllegalArgumentException("Unexpected proof or certificate");
            }
            TransferGrant grant = null;
            if (json.get("transferId") != null) {
                String sha256 = required(json, "sha256Checksum");
                long size = Long.parseLong(required(json, "fileSizeBytes"));
                int chunkSize = Integer.parseInt(required(json, "chunkSizeBytes"));
                if (!sha256.matches("[0-9a-f]{64}") || size < 0 || chunkSize < 1) {
                    throw new IllegalArgumentException("Unexpected transfer metadata");
                }
                grant = new TransferGrant(UUID.fromString(json.get("transferId")),
                        UUID.fromString(required(json, "sharedFileId")), required(json, "fileName"), size, sha256,
                        chunkSize);
            }
            return new VerifiedTicket(UUID.fromString(required(json, "ticketId")),
                    UUID.fromString(required(json, "sourcePeerId")), json.get("sourceDeviceName"),
                    json.get("sourceOwnerUsername"), proof, certificate, grant);
        } catch (IllegalArgumentException e) {
            throw new TicketException(Reason.INVALID_RESPONSE, "Unexpected redemption response from the server");
        }
    }

    private static String required(Map<String, String> json, String key) {
        String value = json.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value;
    }

    /** Accepts only IP literals, so no DNS lookup is ever made for a server-provided host. */
    private static String ipLiteral(String host) {
        if (!IP_LITERAL.matcher(host).matches()) {
            throw new IllegalArgumentException("Not an IP address");
        }
        try {
            return InetAddress.getByName(host).getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Not an IP address", e);
        }
    }

    private URI endpoint(String path) {
        return URI.create(serverUrl + path);
    }
}
