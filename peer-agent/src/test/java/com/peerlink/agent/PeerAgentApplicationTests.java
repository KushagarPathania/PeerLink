package com.peerlink.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeerAgentApplicationTests {

    private static final String PEER_ID = "3f2b8c1e-6d4a-4b7e-9c2d-1a5e8f7b6c3d";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void invalidConfigurationExitsWithCode2() {
        assertThat(PeerAgentApplication.run(new String[] {"--port=0"})).isEqualTo(PeerAgentApplication.EXIT_INVALID_CONFIG);
    }

    @Test
    void stopsWithCode3WhenTheServerRejectsTheCredential(@TempDir Path dir) throws IOException {
        AtomicInteger heartbeats = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/api/peers/" + PEER_ID + "/heartbeat", exchange -> {
            heartbeats.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        Path credentialFile = Files.writeString(dir.resolve("credential"), "plk_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");

        int exitCode = PeerAgentApplication.run(new String[] {
                "--server-url=http://127.0.0.1:" + server.getAddress().getPort(),
                "--peer-id=" + PEER_ID,
                "--credential-file=" + credentialFile});

        assertThat(exitCode).isEqualTo(PeerAgentApplication.EXIT_CREDENTIAL_REJECTED);
        assertThat(heartbeats.get()).isEqualTo(1);
    }

    @Test
    void connectionTestModeExitsWithCode4WhenTargetIsUnavailable(@TempDir Path dir) throws IOException {
        AtomicInteger heartbeats = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/api/peers/" + PEER_ID + "/connections", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/api/peers/" + PEER_ID + "/heartbeat", exchange -> {
            heartbeats.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        Path credentialFile = Files.writeString(dir.resolve("credential"), "plk_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");

        int exitCode = PeerAgentApplication.run(new String[] {
                "--server-url=http://127.0.0.1:" + server.getAddress().getPort(),
                "--peer-id=" + PEER_ID,
                "--credential-file=" + credentialFile,
                "--connect-to=9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d"});

        assertThat(exitCode).isEqualTo(PeerAgentApplication.EXIT_CONNECTION_TEST_FAILED);
        assertThat(heartbeats.get()).isZero(); // a connection test never starts the heartbeat loop
    }

    @Test
    void listenerStartupFailureExitsWithCode5(@TempDir Path dir) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        Path credentialFile = Files.writeString(dir.resolve("credential"), "plk_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");

        try (java.net.ServerSocket occupied = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int exitCode = PeerAgentApplication.run(new String[] {
                    "--server-url=http://127.0.0.1:" + server.getAddress().getPort(),
                    "--peer-id=" + PEER_ID,
                    "--credential-file=" + credentialFile,
                    "--port=" + occupied.getLocalPort()});

            assertThat(exitCode).isEqualTo(PeerAgentApplication.EXIT_LISTENER_FAILED);
        }
    }

    @Test
    void shareModeRegistersTheFileAndExits(@TempDir Path dir) throws IOException {
        java.util.concurrent.atomic.AtomicReference<String> registration = new java.util.concurrent.atomic.AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/api/peers/" + PEER_ID + "/files", exchange -> {
            registration.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] body = "{\"id\":\"9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d\"}".getBytes();
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path credentialFile = Files.writeString(dir.resolve("credential"), "plk_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");
        Path shared = Files.writeString(dir.resolve("hello.txt"), "hello");

        int exitCode = PeerAgentApplication.run(new String[] {
                "--server-url=http://127.0.0.1:" + server.getAddress().getPort(),
                "--peer-id=" + PEER_ID,
                "--credential-file=" + credentialFile,
                "--data-dir=" + dir.resolve("agent"),
                "--share=" + shared});

        assertThat(exitCode).isEqualTo(PeerAgentApplication.EXIT_OK);
        // Only metadata leaves the device: name, size and SHA-256, never the content.
        assertThat(registration.get()).contains("\"fileName\":\"hello.txt\"").contains("\"fileSizeBytes\":5")
                .contains("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824").doesNotContain("\"hello\"");
        assertThat(Files.readString(dir.resolve("agent/shared-files-" + PEER_ID + ".properties")))
                .contains("9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d");
    }

    @Test
    void shareModeFailsWithCode6ForAMissingFile(@TempDir Path dir) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        Path credentialFile = Files.writeString(dir.resolve("credential"), "plk_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");

        assertThat(PeerAgentApplication.run(new String[] {
                "--server-url=http://127.0.0.1:" + server.getAddress().getPort(), "--peer-id=" + PEER_ID,
                "--credential-file=" + credentialFile, "--share=" + dir.resolve("missing.txt")}))
                .isEqualTo(PeerAgentApplication.EXIT_SHARE_FAILED);
    }
}
