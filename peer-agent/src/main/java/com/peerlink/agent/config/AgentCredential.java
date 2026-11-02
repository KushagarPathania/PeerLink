package com.peerlink.agent.config;

import java.util.Objects;

/**
 * The device credential issued by the coordination server when the peer was registered. It is
 * a secret: {@link #toString()} is masked and the value is only ever put in an HTTP header.
 */
public final class AgentCredential {

    public static final String PREFIX = "plk_";

    private final String value;

    private AgentCredential(String value) {
        this.value = value;
    }

    public static AgentCredential of(String value) {
        String trimmed = Objects.requireNonNull(value, "credential").trim();
        if (!trimmed.startsWith(PREFIX) || trimmed.length() < 20 || trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Agent credential is malformed; copy it exactly as shown when the "
                    + "device was registered");
        }
        return new AgentCredential(trimmed);
    }

    /** The secret itself; use only to build the Authorization header. */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return "AgentCredential[****]";
    }
}
