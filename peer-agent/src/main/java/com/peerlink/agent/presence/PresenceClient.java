package com.peerlink.agent.presence;

import com.peerlink.agent.config.AgentCredential;
import java.util.UUID;

/** How the agent reports presence to the coordination server. Implementations must not throw on network errors. */
public interface PresenceClient {

    /**
     * @param listeningPort        the port this agent's peer listener is bound to, or {@code null}
     *                             when it runs no listener (the registered port is then left unchanged)
     * @param tlsCertificateSha256 fingerprint of the certificate the listener presents, or {@code null}
     */
    HeartbeatResult sendHeartbeat(UUID peerId, AgentCredential credential, Integer listeningPort,
                                  String tlsCertificateSha256);
}
