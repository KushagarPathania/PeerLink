package com.peerlink.agent;

import com.peerlink.agent.config.AgentConfig;
import com.peerlink.agent.networking.ConnectionTestResult;
import com.peerlink.agent.networking.CoordinationServerClient;
import com.peerlink.agent.networking.CoordinationServerClient.ServerStatus;
import com.peerlink.agent.networking.CoordinationTicketAuthority;
import com.peerlink.agent.networking.InboundConnectionHandler;
import com.peerlink.agent.networking.PeerConnector;
import com.peerlink.agent.networking.PeerListener;
import com.peerlink.agent.presence.BackoffPolicy;
import com.peerlink.agent.presence.HeartbeatLoop;
import com.peerlink.agent.presence.HeartbeatLoop.StopReason;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Entry point for the PeerLink peer agent, the process that runs on each user's device. Modes:
 * <ul>
 *   <li>no peer configured: check that the coordination server is reachable and exit;</li>
 *   <li>peer configured: keep the peer online with heartbeats and accept authorized direct
 *       connections from other peers until stopped;</li>
 *   <li>{@code --connect-to}: test one direct connection to another peer and exit (a separate
 *       process, so it never delays the heartbeats of a running agent).</li>
 * </ul>
 * File transfer over these connections is added in a later phase.
 */
public final class PeerAgentApplication {

    static {
        // Must run before the first logger is created, so it precedes the LOG field below.
        if (System.getProperty("java.util.logging.SimpleFormatter.format") == null) {
            System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$-7s [peer-agent] %5$s%6$s%n");
        }
    }

    private static final Logger LOG = System.getLogger(PeerAgentApplication.class.getName());

    static final int EXIT_OK = 0;
    static final int EXIT_SERVER_UNAVAILABLE = 1;
    static final int EXIT_INVALID_CONFIG = 2;
    static final int EXIT_CREDENTIAL_REJECTED = 3;
    static final int EXIT_CONNECTION_TEST_FAILED = 4;
    static final int EXIT_LISTENER_FAILED = 5;

    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(30);

    private PeerAgentApplication() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (Arrays.asList(args).contains("--help")) {
            System.out.print(AgentConfig.usage());
            return EXIT_OK;
        }

        AgentConfig config;
        try {
            config = AgentConfig.resolve(args, System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid configuration: " + e.getMessage());
            System.err.print(AgentConfig.usage());
            return EXIT_INVALID_CONFIG;
        }

        LOG.log(Level.INFO, "Starting PeerLink peer agent");
        LOG.log(Level.INFO, "Coordination server: {0}", config.serverUrl());
        LOG.log(Level.INFO, "Peer listen port:    {0,number,#}", config.listenPort());
        LOG.log(Level.INFO, "Download directory:  {0}", config.downloadDirectory());

        CoordinationServerClient client = new CoordinationServerClient(config.serverUrl());
        ServerStatus status = client.checkHealth();
        switch (status) {
            case HEALTHY -> LOG.log(Level.INFO, "Coordination server is healthy");
            case UNHEALTHY -> LOG.log(Level.WARNING, "Coordination server is reachable but reports it is unhealthy");
            case UNREACHABLE -> LOG.log(Level.WARNING, "Coordination server is unreachable at {0}", config.serverUrl());
        }

        if (config.connectTo() != null) {
            return runConnectionTest(config, client);
        }
        if (!config.presenceConfigured()) {
            LOG.log(Level.INFO, "No peer configured. Register this device in the PeerLink dashboard, then start the "
                    + "agent with --peer-id and its agent credential to keep it online.");
            return status == ServerStatus.HEALTHY ? EXIT_OK : EXIT_SERVER_UNAVAILABLE;
        }
        return runHeartbeats(config, client);
    }

    /** One outgoing connection test; never retried. */
    private static int runConnectionTest(AgentConfig config, CoordinationServerClient client) {
        CoordinationTicketAuthority authority =
                new CoordinationTicketAuthority(client, config.peerId(), config.credential());
        PeerConnector connector = new PeerConnector(config.peerId(), authority, PeerConnector.Settings.defaults());
        LOG.log(Level.INFO, "Testing a direct connection from peer {0} to peer {1}", config.peerId(), config.connectTo());
        ConnectionTestResult result = connector.testConnection(config.connectTo());
        if (result.succeeded()) {
            LOG.log(Level.INFO, "Connection test succeeded: {0} (round trip {1} ms)", result.detail(),
                    String.format(java.util.Locale.ROOT, "%.2f", result.roundTrip().toNanos() / 1_000_000.0));
            return EXIT_OK;
        }
        LOG.log(Level.WARNING, "Connection test failed ({0}): {1}", result.outcome(), result.detail());
        return result.outcome() == ConnectionTestResult.Outcome.CREDENTIAL_REJECTED
                ? EXIT_CREDENTIAL_REJECTED : EXIT_CONNECTION_TEST_FAILED;
    }

    /**
     * Keeps the configured peer online until the process is stopped or the credential is rejected,
     * and serves direct connections meanwhile. A failing peer connection never affects heartbeats.
     */
    private static int runHeartbeats(AgentConfig config, CoordinationServerClient client) {
        PeerListener listener = null;
        if (config.listenEnabled()) {
            CoordinationTicketAuthority authority =
                    new CoordinationTicketAuthority(client, config.peerId(), config.credential());
            listener = new PeerListener(
                    new PeerListener.Settings(config.listenHost(), config.listenPort(), config.maxConnections()),
                    new InboundConnectionHandler(config.peerId(), authority, HANDSHAKE_TIMEOUT, IDLE_TIMEOUT));
            try {
                listener.start();
            } catch (IOException e) {
                LOG.log(Level.ERROR, "Cannot listen for peer connections on {0}:{1,number,#}: {2}",
                        config.listenHost().getHostAddress(), config.listenPort(), e.getMessage());
                return EXIT_LISTENER_FAILED;
            }
            if (config.listenHost().isLoopbackAddress()) {
                LOG.log(Level.WARNING, "The peer listener only accepts connections from this machine. Use "
                        + "--listen-host=<this device's LAN IP> (or 0.0.0.0) to accept peers on other devices.");
            } else if (config.listenHost().isAnyLocalAddress()) {
                LOG.log(Level.WARNING, "The peer listener accepts connections on every network interface.");
            }
        }
        try {
            return runHeartbeatLoop(config, client, listener);
        } finally {
            if (listener != null) {
                listener.close();
            }
        }
    }

    private static int runHeartbeatLoop(AgentConfig config, CoordinationServerClient client, PeerListener listener) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "peerlink-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        HeartbeatLoop loop = new HeartbeatLoop(client, config.peerId(), config.credential(),
                config.heartbeatInterval(), BackoffPolicy.standard(), executor,
                listener == null ? null : listener.port());
        Thread shutdownHook = new Thread(() -> {
            loop.close();
            if (listener != null) {
                listener.close();
            }
        }, "peerlink-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        LOG.log(Level.INFO, "Sending heartbeats for peer {0} every {1} s (Ctrl+C to stop)",
                config.peerId(), config.heartbeatInterval().toSeconds());
        try {
            loop.start();
            StopReason reason = loop.awaitStop();
            return reason == StopReason.CREDENTIAL_REJECTED ? EXIT_CREDENTIAL_REJECTED : EXIT_OK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return EXIT_OK;
        } finally {
            loop.close();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // The JVM is already shutting down and the hook is running or has run.
            }
            LOG.log(Level.INFO, "Peer agent stopped");
        }
    }
}
