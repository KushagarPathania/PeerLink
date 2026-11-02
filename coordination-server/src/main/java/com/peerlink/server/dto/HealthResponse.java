package com.peerlink.server.dto;

import java.time.Instant;

public record HealthResponse(Status status, String service, Status database, Instant timestamp) {

    public enum Status {
        UP,
        DOWN
    }
}
