package com.peerlink.server.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peerlink.server.entity.FileTransfer;
import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.PeerStatus;
import com.peerlink.server.entity.SharedFile;
import com.peerlink.server.entity.User;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ResponseMappingTests {

    private static final String PASSWORD_HASH = "$2a$10$secretHashValueThatMustNeverLeak";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");
    private final User owner = new User("owner", "owner@example.com", PASSWORD_HASH);
    private final User requester = new User("requester", "requester@example.com", PASSWORD_HASH);
    private final Peer senderPeer = new Peer(owner, "sender", InetAddress.getLoopbackAddress(), 9090);
    private final Peer receiverPeer = new Peer(requester, "receiver", InetAddress.getLoopbackAddress(), 9091);

    @Test
    void userResponseNeverContainsPasswordData() throws Exception {
        String json = objectMapper.writeValueAsString(UserResponse.from(owner));

        assertThat(json).contains("\"username\":\"owner\"").doesNotContainIgnoringCase("password")
                .doesNotContain(PASSWORD_HASH);
    }

    @Test
    void peerResponseReportsStalePeersAsOffline() {
        senderPeer.recordHeartbeat(InetAddress.getLoopbackAddress(), 9090, now);

        assertThat(PeerResponse.from(senderPeer, now.plusSeconds(30), Duration.ofSeconds(90)).status())
                .isEqualTo(PeerStatus.ONLINE);
        assertThat(PeerResponse.from(senderPeer, now.plusSeconds(91), Duration.ofSeconds(90)).status())
                .isEqualTo(PeerStatus.OFFLINE);
    }

    @Test
    void responsesExposeNoSecretsOrNestedEntities() throws Exception {
        SharedFile file = new SharedFile(senderPeer, "a.txt", 10, "a".repeat(64), 4);
        FileTransfer transfer = new FileTransfer(file, receiverPeer, requester);

        String json = objectMapper.writeValueAsString(new Object[] {
                PeerResponse.from(senderPeer, now, Duration.ofSeconds(90)),
                SharedFileResponse.from(file),
                FileTransferResponse.from(transfer)});

        assertThat(json).doesNotContainIgnoringCase("password").doesNotContain(PASSWORD_HASH)
                .doesNotContain("owner@example.com").contains("\"fileName\":\"a.txt\"");
    }
}
