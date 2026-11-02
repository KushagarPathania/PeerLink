package com.peerlink.server.dto;

import com.peerlink.server.entity.Peer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Optional heartbeat body. An agent that runs a listener reports the port it actually listens on,
 * so the registered port cannot drift from reality. The address is never taken from the body.
 */
public record HeartbeatRequest(@Min(Peer.MIN_PORT) @Max(Peer.MAX_PORT) Integer listeningPort) {
}
