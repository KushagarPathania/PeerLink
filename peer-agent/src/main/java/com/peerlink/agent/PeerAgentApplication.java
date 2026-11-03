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
import com.peerlink.agent.security.PeerTls;
import com.peerlink.agent.security.TlsIdentity;
import com.peerlink.agent.transfer.CompletionReports;
import com.peerlink.agent.transfer.CoordinationException;
import com.peerlink.agent.transfer.CoordinationTransferCoordinator;
import com.peerlink.agent.transfer.DownloadManager;
import com.peerlink.agent.transfer.DownloadSession;
import com.peerlink.agent.transfer.FileSharer;
import com.peerlink.agent.transfer.ShareIndex;
import com.peerlink.agent.transfer.TransferCoordinator;
import com.peerlink.agent.transfer.UploadSession;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Entry point for the PeerLink peer agent, the process that runs on each user's device. Modes:
 * <ul>
 *   <li>no peer configured: check that the coordination server is reachable and exit;</li>
 *   <li>peer configured: keep the peer online with heartbeats, serve files shared from this
 *       device to authorized peers, and fetch files the user requested to this device, until stopped;</li>
 *   <li>{@code --connect-to}: test one direct connection to another peer and exit;</li>
 *   <li>{@code --share}: share one local file and exit.</li>
 * </ul>
 * All peer-to-peer traffic is mutually authenticated TLS 1.3 using a key pair generated for this
 * process; the coordination server distributes the certificate fingerprints.
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
    static final int EXIT_SHARE_FAILED = 6;

    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration AUTHORIZATION_CHECK_INTERVAL = Duration.ofSeconds(5);
    private static final Duration PROGRESS_INTERVAL = Duration.ofSeconds(2);
    private static final Duration TRANSFER_POLL_INTERVAL = Duration.ofSeconds(5);
    /** Completion reports are retried for this long (well within the server's 3-minute stall timeout)... */
    private static final Duration COMPLETION_REPORT_DEADLINE = Duration.ofSeconds(60);
    /** ...starting this far apart and doubling up to 5 s; then on every poll. */
    private static final Duration COMPLETION_REPORT_BACKOFF = Duration.ofMillis(500);

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
        warnIfServerConnectionIsUnencrypted(config);

        CoordinationServerClient client = new CoordinationServerClient(config.serverUrl());
        ServerStatus status = client.checkHealth();
        switch (status) {
            case HEALTHY -> LOG.log(Level.INFO, "Coordination server is healthy");
            case UNHEALTHY -> LOG.log(Level.WARNING, "Coordination server is reachable but reports it is unhealthy");
            case UNREACHABLE -> LOG.log(Level.WARNING, "Coordination server is unreachable at {0}", config.serverUrl());
        }

        if (config.sharePath() != null) {
            return runShare(config, client);
        }
        if (config.connectTo() != null) {
            return runConnectionTest(config, client);
        }
        if (!config.presenceConfigured()) {
            LOG.log(Level.INFO, "No peer configured. Register this device in the PeerLink dashboard, then start the "
                    + "agent with --peer-id and its agent credential to keep it online.");
            return status == ServerStatus.HEALTHY ? EXIT_OK : EXIT_SERVER_UNAVAILABLE;
        }
        return runAgent(config, client);
    }

    /** Shares one file: hash locally, register name/size/SHA-256 with the server, remember the path locally. */
    private static int runShare(AgentConfig config, CoordinationServerClient client) {
        TransferCoordinator coordinator =
                new CoordinationTransferCoordinator(client, config.peerId(), config.credential());
        FileSharer sharer = new FileSharer(coordinator,
                ShareIndex.forPeer(config.dataDirectory(), config.peerId()), config.maxFileSizeBytes());
        try {
            FileSharer.Shared shared = sharer.share(config.sharePath());
            LOG.log(Level.INFO, "Shared \"{0}\" ({1} bytes, SHA-256 {2}) as file {3}. Grant access to other users "
                    + "in the dashboard; keep the agent running so they can fetch it.", shared.fileName(),
                    shared.sizeBytes(), shared.sha256(), shared.fileId());
            return EXIT_OK;
        } catch (IllegalArgumentException | IOException e) {
            LOG.log(Level.ERROR, "Cannot share {0}: {1}", config.sharePath(), e.getMessage());
            return EXIT_SHARE_FAILED;
        } catch (CoordinationException e) {
            LOG.log(Level.ERROR, "The coordination server did not accept the file: {0}", e.getMessage());
            return e.credentialRejected() ? EXIT_CREDENTIAL_REJECTED : EXIT_SHARE_FAILED;
        }
    }

    /** One outgoing connection test; never retried. */
    private static int runConnectionTest(AgentConfig config, CoordinationServerClient client) {
        PeerTls tls = new PeerTls(TlsIdentity.generate(config.peerId().toString()));
        CoordinationTicketAuthority authority = new CoordinationTicketAuthority(client, config.peerId(),
                config.credential(), tls.identity().fingerprint());
        PeerConnector connector = new PeerConnector(config.peerId(), authority, tls, PeerConnector.Settings.defaults());
        LOG.log(Level.INFO, "Testing a direct connection from peer {0} to peer {1}", config.peerId(), config.connectTo());
        ConnectionTestResult result = connector.testConnection(config.connectTo());
        if (result.succeeded()) {
            LOG.log(Level.INFO, "Connection test succeeded: {0} (round trip {1} ms)", result.detail(),
                    String.format(Locale.ROOT, "%.2f", result.roundTrip().toNanos() / 1_000_000.0));
            return EXIT_OK;
        }
        LOG.log(Level.WARNING, "Connection test failed ({0}): {1}", result.outcome(), result.detail());
        return result.outcome() == ConnectionTestResult.Outcome.CREDENTIAL_REJECTED
                ? EXIT_CREDENTIAL_REJECTED : EXIT_CONNECTION_TEST_FAILED;
    }

    /**
     * Keeps the configured peer online until the process is stopped or the credential is rejected,
     * serving and fetching files meanwhile. Peer connections and transfers run on their own threads,
     * so they can never delay or stop heartbeats.
     */
    private static int runAgent(AgentConfig config, CoordinationServerClient client) {
        PeerTls tls = new PeerTls(TlsIdentity.generate(config.peerId().toString()));
        LOG.log(Level.INFO, "TLS certificate fingerprint of this agent: {0}", tls.identity().fingerprint());
        CoordinationTicketAuthority authority = new CoordinationTicketAuthority(client, config.peerId(),
                config.credential(), tls.identity().fingerprint());
        TransferCoordinator coordinator =
                new CoordinationTransferCoordinator(client, config.peerId(), config.credential());

        PeerListener listener = null;
        UploadSession uploads = null;
        if (config.listenEnabled()) {
            uploads = new UploadSession(ShareIndex.forPeer(config.dataDirectory(), config.peerId()),
                    coordinator, config.maxTransfers(), IDLE_TIMEOUT, AUTHORIZATION_CHECK_INTERVAL);
            listener = new PeerListener(
                    new PeerListener.Settings(config.listenHost(), config.listenPort(), config.maxConnections()),
                    new InboundConnectionHandler(config.peerId(), authority, tls, uploads, HANDSHAKE_TIMEOUT, IDLE_TIMEOUT),
                    tls);
            try {
                listener.start();
            } catch (IOException e) {
                LOG.log(Level.ERROR, "Cannot listen for peer connections on {0}:{1,number,#}: {2}",
                        config.listenHost().getHostAddress(), config.listenPort(), e.getMessage());
                listener.close();
                uploads.close();
                return EXIT_LISTENER_FAILED;
            }
            if (config.listenHost().isLoopbackAddress()) {
                LOG.log(Level.WARNING, "The peer listener only accepts connections from this machine. Use "
                        + "--listen-host=<this device's LAN IP> (or 0.0.0.0) to accept peers on other devices.");
            } else if (config.listenHost().isAnyLocalAddress()) {
                LOG.log(Level.WARNING, "The peer listener accepts connections on every network interface.");
            }
        }

        PeerConnector connector = new PeerConnector(config.peerId(), authority, tls, PeerConnector.Settings.defaults());
        // Completion reports the server has not confirmed survive restarts and are retried on every poll.
        CompletionReports completionReports = new CompletionReports(coordinator,
                config.dataDirectory().resolve("completion-reports-" + config.peerId()),
                COMPLETION_REPORT_BACKOFF, COMPLETION_REPORT_DEADLINE);
        DownloadManager downloads = new DownloadManager(coordinator,
                handle -> new DownloadSession(connector, coordinator, config.downloadDirectory(),
                        config.maxFileSizeBytes(), IDLE_TIMEOUT, PROGRESS_INTERVAL, handle, completionReports),
                config.maxTransfers(), TRANSFER_POLL_INTERVAL, completionReports);
        downloads.start();
        UploadSession finalUploads = uploads;
        PeerListener finalListener = listener;
        Runnable stopTransfers = () -> {
            downloads.close();
            if (finalUploads != null) {
                finalUploads.close(); // first, so running uploads end as AGENT_SHUTDOWN
            }
            if (finalListener != null) {
                finalListener.close();
            }
        };
        try {
            return runHeartbeatLoop(config, client, listener, stopTransfers, tls);
        } finally {
            stopTransfers.run();
        }
    }

    private static int runHeartbeatLoop(AgentConfig config, CoordinationServerClient client, PeerListener listener,
                                        Runnable stopTransfers, PeerTls tls) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "peerlink-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        HeartbeatLoop loop = new HeartbeatLoop(client, config.peerId(), config.credential(),
                config.heartbeatInterval(), BackoffPolicy.standard(), executor,
                listener == null ? null : listener.port(), listener == null ? null : tls.identity().fingerprint());
        Thread shutdownHook = new Thread(() -> {
            loop.close();
            stopTransfers.run();
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

    /**
     * Peer fingerprints and the agent credential travel over the agent-to-server connection, so it
     * must be HTTPS whenever the network between them is not trusted.
     */
    private static void warnIfServerConnectionIsUnencrypted(AgentConfig config) {
        String host = config.serverUrl().getHost();
        boolean loopback = host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]");
        if ("http".equalsIgnoreCase(config.serverUrl().getScheme()) && !loopback) {
            LOG.log(Level.WARNING, "The coordination server URL uses plain HTTP. The agent credential and the TLS "
                    + "fingerprints that protect peer connections travel over it; use HTTPS on untrusted networks.");
        }
    }
}
