package com.peerlink.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentConfigTests {

    private static final String[] NO_ARGS = {};
    private static final String CREDENTIAL = "plk_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    private static final String PEER_ID = "3f2b8c1e-6d4a-4b7e-9c2d-1a5e8f7b6c3d";

    @Test
    void usesDefaultsWhenNothingIsConfigured() {
        AgentConfig config = AgentConfig.resolve(NO_ARGS, Map.of());

        assertThat(config.serverUrl()).isEqualTo(URI.create(AgentConfig.DEFAULT_SERVER_URL));
        assertThat(config.listenPort()).isEqualTo(AgentConfig.DEFAULT_LISTEN_PORT);
        assertThat(config.downloadDirectory()).isAbsolute().endsWithRaw(Path.of("PeerLink", "downloads"));
    }

    @Test
    void environmentOverridesDefaults() {
        Map<String, String> env = Map.of(
                AgentConfig.ENV_SERVER_URL, "http://192.168.1.10:8080",
                AgentConfig.ENV_PORT, "9100");

        AgentConfig config = AgentConfig.resolve(NO_ARGS, env);

        assertThat(config.serverUrl()).isEqualTo(URI.create("http://192.168.1.10:8080"));
        assertThat(config.listenPort()).isEqualTo(9100);
    }

    @Test
    void commandLineOverridesEnvironment() {
        Map<String, String> env = Map.of(AgentConfig.ENV_PORT, "9100");

        AgentConfig config = AgentConfig.resolve(new String[] {"--port=9200", "--download-dir=/tmp/peerlink"}, env);

        assertThat(config.listenPort()).isEqualTo(9200);
        assertThat(config.downloadDirectory()).isEqualTo(Path.of("/tmp/peerlink"));
    }

    @Test
    void stripsTrailingSlashFromServerUrl() {
        AgentConfig config = AgentConfig.resolve(new String[] {"--server-url=http://localhost:8080/"}, Map.of());

        assertThat(config.serverUrl()).isEqualTo(URI.create("http://localhost:8080"));
    }

    @Test
    void rejectsOutOfRangePort() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--port=70000"}, Map.of()))
                .withMessageContaining("between 1 and 65535");
    }

    @Test
    void rejectsNonNumericPort() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--port=abc"}, Map.of()))
                .withMessageContaining("must be a number");
    }

    @Test
    void rejectsNonHttpServerUrl() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--server-url=ftp://example.com"}, Map.of()));
    }

    @Test
    void rejectsUnknownOption() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--shared-root=/"}, Map.of()))
                .withMessageContaining("Unknown option");
    }

    @Test
    void rejectsMalformedArgument() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"9090"}, Map.of()));
    }

    @Test
    void presenceIsOffByDefault() {
        AgentConfig config = AgentConfig.resolve(NO_ARGS, Map.of());

        assertThat(config.presenceConfigured()).isFalse();
        assertThat(config.heartbeatInterval()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void readsPeerIdAndCredentialFromEnvironment() {
        AgentConfig config = AgentConfig.resolve(new String[] {"--heartbeat-interval=30"},
                Map.of(AgentConfig.ENV_PEER_ID, PEER_ID, AgentConfig.ENV_CREDENTIAL, CREDENTIAL));

        assertThat(config.presenceConfigured()).isTrue();
        assertThat(config.peerId()).isEqualTo(UUID.fromString(PEER_ID));
        assertThat(config.credential().value()).isEqualTo(CREDENTIAL);
        assertThat(config.heartbeatInterval()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void readsCredentialFromFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("agent.credential");
        Files.writeString(file, CREDENTIAL + "\n");

        AgentConfig config = AgentConfig.resolve(
                new String[] {"--peer-id=" + PEER_ID, "--credential-file=" + file}, Map.of());

        assertThat(config.credential().value()).isEqualTo(CREDENTIAL);
    }

    @Test
    void neverPrintsTheCredential() {
        AgentConfig config = AgentConfig.resolve(NO_ARGS,
                Map.of(AgentConfig.ENV_PEER_ID, PEER_ID, AgentConfig.ENV_CREDENTIAL, CREDENTIAL));

        assertThat(config.toString()).doesNotContain(CREDENTIAL).contains("****");
        assertThat(config.credential().toString()).doesNotContain(CREDENTIAL);
    }

    @Test
    void rejectsCredentialOnTheCommandLine() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--credential=" + CREDENTIAL}, Map.of()))
                .withMessageContaining("Unknown option");
    }

    @Test
    void requiresPeerIdAndCredentialTogether() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--peer-id=" + PEER_ID}, Map.of()))
                .withMessageContaining("together");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(NO_ARGS, Map.of(AgentConfig.ENV_CREDENTIAL, CREDENTIAL)));
    }

    @Test
    void rejectsInvalidPresenceSettings() {
        Map<String, String> env = Map.of(AgentConfig.ENV_CREDENTIAL, CREDENTIAL);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--peer-id=laptop"}, env))
                .withMessageContaining("UUID");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--peer-id=" + PEER_ID}, Map.of(
                        AgentConfig.ENV_CREDENTIAL, "my-password")))
                .withMessageContaining("malformed");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--heartbeat-interval=0"}, Map.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--credential-file=/does/not/exist",
                        "--peer-id=" + PEER_ID}, Map.of()))
                .withMessageContaining("Cannot read credential file");
    }

    @Test
    void listenerDefaultsToLoopbackAndIsEnabled() {
        AgentConfig config = AgentConfig.resolve(NO_ARGS, Map.of());

        assertThat(config.listenHost().isLoopbackAddress()).isTrue();
        assertThat(config.listenEnabled()).isTrue();
        assertThat(config.maxConnections()).isEqualTo(AgentConfig.DEFAULT_MAX_CONNECTIONS);
        assertThat(config.connectTo()).isNull();
    }

    @Test
    void readsListenerSettingsFromEnvironmentAndCommandLine() {
        AgentConfig config = AgentConfig.resolve(new String[] {"--listen-host=0.0.0.0", "--max-connections=4"},
                Map.of(AgentConfig.ENV_LISTEN, "false", AgentConfig.ENV_LISTEN_HOST, "192.168.1.5"));

        assertThat(config.listenHost().isAnyLocalAddress()).isTrue();
        assertThat(config.listenEnabled()).isFalse();
        assertThat(config.maxConnections()).isEqualTo(4);
    }

    @Test
    void rejectsHostNamesAndInvalidListenerSettings() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--listen-host=my-laptop.example.com"}, Map.of()))
                .withMessageContaining("IP address");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--listen=maybe"}, Map.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--max-connections=0"}, Map.of()));
    }

    @Test
    void connectToRequiresOwnPeerAndAnotherTarget() {
        Map<String, String> env = Map.of(AgentConfig.ENV_PEER_ID, PEER_ID, AgentConfig.ENV_CREDENTIAL, CREDENTIAL);
        String other = "9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d";

        assertThat(AgentConfig.resolve(new String[] {"--connect-to=" + other}, env).connectTo())
                .isEqualTo(UUID.fromString(other));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--connect-to=" + other}, Map.of()))
                .withMessageContaining("peer id");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--connect-to=" + PEER_ID}, env))
                .withMessageContaining("another peer");
    }

    @Test
    void transferSettingsHaveSafeDefaults() {
        AgentConfig config = AgentConfig.resolve(NO_ARGS, Map.of());

        assertThat(config.maxFileSizeBytes()).isEqualTo(1024L * 1024 * 1024);
        assertThat(config.maxTransfers()).isEqualTo(2);
        assertThat(config.dataDirectory()).isAbsolute().endsWithRaw(Path.of("PeerLink", "agent"));
        assertThat(config.sharePath()).isNull();
    }

    @Test
    void parsesHumanReadableFileSizes() {
        assertThat(AgentConfig.parseSize("max-file-size", "1048576")).isEqualTo(1L << 20);
        assertThat(AgentConfig.parseSize("max-file-size", "500MiB")).isEqualTo(500L << 20);
        assertThat(AgentConfig.parseSize("max-file-size", "2g")).isEqualTo(2L << 30);
        assertThat(AgentConfig.parseSize("max-file-size", "64 KB")).isEqualTo(64L << 10);
        assertThatIllegalArgumentException().isThrownBy(() -> AgentConfig.parseSize("max-file-size", "-1"));
        assertThatIllegalArgumentException().isThrownBy(() -> AgentConfig.parseSize("max-file-size", "1TB"));
        assertThatIllegalArgumentException().isThrownBy(() -> AgentConfig.parseSize("max-file-size", "9999999999999G"));
    }

    @Test
    void readsTransferSettingsFromEnvironmentAndCommandLine() {
        AgentConfig config = AgentConfig.resolve(new String[] {"--max-transfers=4", "--data-dir=/tmp/peerlink-data"},
                Map.of(AgentConfig.ENV_MAX_FILE_SIZE, "10MiB"));

        assertThat(config.maxTransfers()).isEqualTo(4);
        assertThat(config.maxFileSizeBytes()).isEqualTo(10L << 20);
        assertThat(config.dataDirectory()).isEqualTo(Path.of("/tmp/peerlink-data"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--max-transfers=0"}, Map.of()));
    }

    @Test
    void shareRequiresOwnPeerAndCannotBeCombinedWithConnectionTest() {
        Map<String, String> env = Map.of(AgentConfig.ENV_PEER_ID, PEER_ID, AgentConfig.ENV_CREDENTIAL, CREDENTIAL);

        assertThat(AgentConfig.resolve(new String[] {"--share=report.pdf"}, env).sharePath()).isEqualTo(Path.of("report.pdf"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--share=report.pdf"}, Map.of()))
                .withMessageContaining("peer id");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfig.resolve(new String[] {"--share=a", "--connect-to=" + UUID.randomUUID()}, env))
                .withMessageContaining("cannot be combined");
    }
}
