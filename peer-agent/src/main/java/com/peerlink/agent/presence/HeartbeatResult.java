package com.peerlink.agent.presence;

/**
 * Outcome of one heartbeat.
 *
 * @param statusCode      HTTP status, or 0 if the server was not reached
 * @param observedAddress the address the server saw this device connect from, if reported
 */
public record HeartbeatResult(Outcome outcome, int statusCode, String observedAddress) {

    public enum Outcome {
        /** The server recorded the heartbeat. */
        ACCEPTED,
        /** The credential is invalid, revoked, or not for this peer: retrying will not help. */
        REJECTED,
        /** The server answered with an error that may be temporary. */
        SERVER_ERROR,
        /** No answer: connection refused, DNS failure or timeout. */
        UNREACHABLE
    }

    public static HeartbeatResult unreachable() {
        return new HeartbeatResult(Outcome.UNREACHABLE, 0, null);
    }

    public static HeartbeatResult fromStatus(int statusCode, String observedAddress) {
        Outcome outcome = switch (statusCode) {
            case 200 -> Outcome.ACCEPTED;
            case 401, 403, 404 -> Outcome.REJECTED;
            default -> Outcome.SERVER_ERROR;
        };
        return new HeartbeatResult(outcome, statusCode, outcome == Outcome.ACCEPTED ? observedAddress : null);
    }
}
