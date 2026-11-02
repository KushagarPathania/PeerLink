package com.peerlink.agent.networking;

import com.peerlink.agent.security.ConnectionOutcome;
import java.time.Duration;
import java.util.UUID;

/**
 * How an outgoing connection test ended. {@code detail} is safe to show and log.
 *
 * @param roundTrip PING/PONG round trip on success, otherwise {@code null}
 * @param ticketId  the ticket used, or {@code null} if none could be obtained
 */
public record ConnectionTestResult(Outcome outcome, String detail, Duration roundTrip, UUID ticketId) {

    public enum Outcome {
        SUCCEEDED(ConnectionOutcome.SUCCEEDED),
        TARGET_UNAVAILABLE(null),
        CREDENTIAL_REJECTED(null),
        SERVER_UNAVAILABLE(null),
        CONNECT_FAILED(ConnectionOutcome.CONNECT_FAILED),
        TIMEOUT(ConnectionOutcome.TIMEOUT),
        REJECTED_BY_PEER(ConnectionOutcome.REJECTED_BY_PEER),
        PROTOCOL_ERROR(ConnectionOutcome.PROTOCOL_ERROR),
        PEER_IDENTITY_MISMATCH(ConnectionOutcome.PEER_IDENTITY_MISMATCH);

        private final ConnectionOutcome reported;

        Outcome(ConnectionOutcome reported) {
            this.reported = reported;
        }

        /** What to report to the server, or {@code null} when no ticket was issued. */
        public ConnectionOutcome reported() {
            return reported;
        }
    }

    public boolean succeeded() {
        return outcome == Outcome.SUCCEEDED;
    }
}
