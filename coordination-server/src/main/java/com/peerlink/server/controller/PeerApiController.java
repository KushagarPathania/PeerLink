package com.peerlink.server.controller;

import com.peerlink.server.dto.AgentCredentialResponse;
import com.peerlink.server.dto.DiscoveredPeerResponse;
import com.peerlink.server.dto.HeartbeatRequest;
import com.peerlink.server.dto.HeartbeatResponse;
import com.peerlink.server.dto.PageResponse;
import com.peerlink.server.dto.PeerRegistrationRequest;
import com.peerlink.server.dto.PeerRegistrationResponse;
import com.peerlink.server.dto.PeerResponse;
import com.peerlink.server.security.AuthenticatedUser;
import com.peerlink.server.security.PeerAgentPrincipal;
import com.peerlink.server.service.PeerPresenceService;
import com.peerlink.server.service.PeerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Peer registration, presence and discovery. All endpoints except the heartbeat are for signed-in
 * users (session + CSRF); the heartbeat is only for peer agents using their bearer credential.
 */
@RestController
@RequestMapping("/api/peers")
public class PeerApiController {

    private final PeerService peerService;
    private final PeerPresenceService presenceService;

    public PeerApiController(PeerService peerService, PeerPresenceService presenceService) {
        this.peerService = peerService;
        this.presenceService = presenceService;
    }

    @PostMapping
    public ResponseEntity<PeerRegistrationResponse> register(@AuthenticationPrincipal AuthenticatedUser user,
                                                             @Valid @RequestBody PeerRegistrationRequest request) {
        PeerRegistrationResponse registration = peerService.register(user.getId(), request);
        return ResponseEntity.created(URI.create("/api/peers/" + registration.peer().id())).body(registration);
    }

    @GetMapping("/mine")
    public List<PeerResponse> myPeers(@AuthenticationPrincipal AuthenticatedUser user) {
        return peerService.listOwnPeers(user.getId());
    }

    @GetMapping("/online")
    public PageResponse<DiscoveredPeerResponse> onlinePeers(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return peerService.discoverOnlinePeers(user.getId(), page, size);
    }

    @GetMapping("/{peerId}")
    public PeerResponse peer(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID peerId) {
        return peerService.getOwnPeer(user.getId(), peerId);
    }

    /**
     * Time and address always come from the server; only the agent's listening port may be
     * reported in the optional body. Any other field, such as a client timestamp, is ignored.
     */
    @PostMapping("/{peerId}/heartbeat")
    public HeartbeatResponse heartbeat(@AuthenticationPrincipal PeerAgentPrincipal agent, @PathVariable UUID peerId,
                                       @Valid @RequestBody(required = false) HeartbeatRequest body,
                                       HttpServletRequest request) {
        Integer listeningPort = body == null ? null : body.listeningPort();
        return presenceService.recordHeartbeat(peerId, agent, remoteAddress(request), listeningPort);
    }

    @PostMapping("/{peerId}/credential")
    public AgentCredentialResponse rotateCredential(@AuthenticationPrincipal AuthenticatedUser user,
                                                    @PathVariable UUID peerId) {
        return peerService.rotateCredential(user.getId(), peerId);
    }

    @DeleteMapping("/{peerId}/credential")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeCredential(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID peerId) {
        peerService.revokeCredential(user.getId(), peerId);
    }

    /**
     * The TCP peer address of this request. Forwarded headers are not trusted unless the server is
     * explicitly configured for a reverse proxy (server.forward-headers-strategy).
     */
    private static InetAddress remoteAddress(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr()); // always an IP literal: no DNS lookup
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Servlet container reported an invalid remote address", e);
        }
    }
}
