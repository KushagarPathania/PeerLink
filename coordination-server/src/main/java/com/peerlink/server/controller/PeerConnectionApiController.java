package com.peerlink.server.controller;

import com.peerlink.server.dto.ConnectionOutcomeRequest;
import com.peerlink.server.dto.ConnectionTicketRequest;
import com.peerlink.server.dto.ConnectionTicketResponse;
import com.peerlink.server.dto.TicketRedemptionRequest;
import com.peerlink.server.dto.TicketRedemptionResponse;
import com.peerlink.server.security.PeerAgentPrincipal;
import com.peerlink.server.service.ConnectionTicketService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authorization for direct peer-to-peer connections (see docs/TCP_PROTOCOL.md). Only peer agents
 * may call these endpoints, each acting solely for its own peer ({@code peerId}).
 */
@RestController
@RequestMapping("/api/peers/{peerId}/connections")
public class PeerConnectionApiController {

    private final ConnectionTicketService ticketService;

    public PeerConnectionApiController(ConnectionTicketService ticketService) {
        this.ticketService = ticketService;
    }

    /** The calling agent (peer {@code peerId}) wants to connect to another peer. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionTicketResponse requestTicket(@AuthenticationPrincipal PeerAgentPrincipal agent,
                                                  @PathVariable UUID peerId,
                                                  @Valid @RequestBody ConnectionTicketRequest request) {
        return ticketService.issue(agent, peerId, request.targetPeerId());
    }

    /** The calling agent (peer {@code peerId}) verifies a ticket a connecting peer presented to it. */
    @PostMapping("/redeem")
    public TicketRedemptionResponse redeem(@AuthenticationPrincipal PeerAgentPrincipal agent,
                                           @PathVariable UUID peerId,
                                           @Valid @RequestBody TicketRedemptionRequest request) {
        return ticketService.redeem(agent, peerId, request.ticket());
    }

    @PostMapping("/{ticketId}/outcome")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reportOutcome(@AuthenticationPrincipal PeerAgentPrincipal agent, @PathVariable UUID peerId,
                              @PathVariable UUID ticketId, @Valid @RequestBody ConnectionOutcomeRequest request) {
        ticketService.reportOutcome(agent, peerId, ticketId, request.outcome());
    }
}
