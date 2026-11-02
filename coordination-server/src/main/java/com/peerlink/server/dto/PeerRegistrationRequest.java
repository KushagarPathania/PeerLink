package com.peerlink.server.dto;

import com.peerlink.server.entity.Peer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Sent by a peer agent when it registers. There is intentionally no address field: the server
 * records the address it observes on the connection rather than trusting a self-reported one.
 */
public record PeerRegistrationRequest(
        @NotBlank
        @Size(max = Peer.DEVICE_NAME_MAX_LENGTH)
        String deviceName,

        @NotNull
        @Min(Peer.MIN_PORT)
        @Max(Peer.MAX_PORT)
        Integer listeningPort) {
}
