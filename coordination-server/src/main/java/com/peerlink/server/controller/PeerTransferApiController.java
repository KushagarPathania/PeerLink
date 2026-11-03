package com.peerlink.server.controller;

import com.peerlink.server.dto.PendingTransferResponse;
import com.peerlink.server.dto.SharedFileRegistrationRequest;
import com.peerlink.server.dto.SharedFileResponse;
import com.peerlink.server.dto.TransferCompletionRequest;
import com.peerlink.server.dto.TransferFailureRequest;
import com.peerlink.server.dto.TransferProgressRequest;
import com.peerlink.server.dto.TransferStatusResponse;
import com.peerlink.server.security.PeerAgentPrincipal;
import com.peerlink.server.service.SharedFileService;
import com.peerlink.server.service.TransferAgentService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * File sharing and transfer coordination for peer agents (bearer credential, each only for its
 * own peer). Agents send metadata and outcomes here; file bytes go only between the agents.
 */
@RestController
@RequestMapping("/api/peers/{peerId}")
public class PeerTransferApiController {

    private final SharedFileService fileService;
    private final TransferAgentService transferService;

    public PeerTransferApiController(SharedFileService fileService, TransferAgentService transferService) {
        this.fileService = fileService;
        this.transferService = transferService;
    }

    /** The agent registers a file its user chose to share from this device. */
    @PostMapping("/files")
    public ResponseEntity<SharedFileResponse> registerFile(@AuthenticationPrincipal PeerAgentPrincipal agent,
                                                           @PathVariable UUID peerId,
                                                           @Valid @RequestBody SharedFileRegistrationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(fileService.registerFromAgent(agent, peerId, request));
    }

    /** The next transfer this (receiving) peer should fetch; 204 when there is none. */
    @GetMapping("/transfers/next")
    public ResponseEntity<PendingTransferResponse> next(@AuthenticationPrincipal PeerAgentPrincipal agent,
                                                        @PathVariable UUID peerId) {
        return transferService.next(agent, peerId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/transfers/{transferId}")
    public TransferStatusResponse status(@AuthenticationPrincipal PeerAgentPrincipal agent, @PathVariable UUID peerId,
                                         @PathVariable UUID transferId) {
        return transferService.status(agent, peerId, transferId);
    }

    @PostMapping("/transfers/{transferId}/progress")
    public TransferStatusResponse progress(@AuthenticationPrincipal PeerAgentPrincipal agent, @PathVariable UUID peerId,
                                           @PathVariable UUID transferId,
                                           @Valid @RequestBody TransferProgressRequest request) {
        return transferService.progress(agent, peerId, transferId, request);
    }

    @PostMapping("/transfers/{transferId}/complete")
    public TransferStatusResponse complete(@AuthenticationPrincipal PeerAgentPrincipal agent, @PathVariable UUID peerId,
                                           @PathVariable UUID transferId,
                                           @Valid @RequestBody TransferCompletionRequest request) {
        return transferService.complete(agent, peerId, transferId, request);
    }

    @PostMapping("/transfers/{transferId}/fail")
    public TransferStatusResponse fail(@AuthenticationPrincipal PeerAgentPrincipal agent, @PathVariable UUID peerId,
                                       @PathVariable UUID transferId, @Valid @RequestBody TransferFailureRequest request) {
        return transferService.fail(agent, peerId, transferId, request.errorCode());
    }

    @PostMapping("/transfers/{transferId}/reject")
    public TransferStatusResponse reject(@AuthenticationPrincipal PeerAgentPrincipal agent, @PathVariable UUID peerId,
                                         @PathVariable UUID transferId, @Valid @RequestBody TransferFailureRequest request) {
        return transferService.reject(agent, peerId, transferId, request.errorCode());
    }
}
