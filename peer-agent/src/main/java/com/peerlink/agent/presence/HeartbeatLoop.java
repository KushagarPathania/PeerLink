package com.peerlink.agent.presence;

import com.peerlink.agent.config.AgentCredential;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends heartbeats on a single background thread until closed or until the server rejects the
 * credential. Network failures never end the loop; they are retried with {@link BackoffPolicy}.
 * Each run schedules the next one, so a slow request never causes overlapping heartbeats.
 */
public final class HeartbeatLoop implements AutoCloseable {

    public enum StopReason {
        SHUTDOWN,
        CREDENTIAL_REJECTED
    }

    private static final Logger LOG = System.getLogger(HeartbeatLoop.class.getName());
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final PresenceClient client;
    private final UUID peerId;
    private final AgentCredential credential;
    private final Duration interval;
    private final BackoffPolicy backoff;
    private final ScheduledExecutorService executor;
    private final Integer listeningPort;
    private final String tlsCertificateSha256;
    private final CompletableFuture<StopReason> stopped = new CompletableFuture<>();

    // Only touched on the executor thread.
    private int consecutiveFailures;
    private String lastObservedAddress;

    public HeartbeatLoop(PresenceClient client, UUID peerId, AgentCredential credential, Duration interval,
                         BackoffPolicy backoff, ScheduledExecutorService executor) {
        this(client, peerId, credential, interval, backoff, executor, null, null);
    }

    /**
     * @param listeningPort        reported with every heartbeat, or {@code null} if this agent runs no listener
     * @param tlsCertificateSha256 fingerprint of the listener's TLS certificate, reported with it
     */
    public HeartbeatLoop(PresenceClient client, UUID peerId, AgentCredential credential, Duration interval,
                         BackoffPolicy backoff, ScheduledExecutorService executor, Integer listeningPort,
                         String tlsCertificateSha256) {
        this.listeningPort = listeningPort;
        this.tlsCertificateSha256 = tlsCertificateSha256;
        this.client = Objects.requireNonNull(client);
        this.peerId = Objects.requireNonNull(peerId);
        this.credential = Objects.requireNonNull(credential);
        this.interval = Objects.requireNonNull(interval);
        this.backoff = Objects.requireNonNull(backoff);
        this.executor = Objects.requireNonNull(executor);
    }

    public void start() {
        scheduleNext(Duration.ZERO);
    }

    /** Blocks until the loop stops and returns why. */
    public StopReason awaitStop() throws InterruptedException {
        try {
            return stopped.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Heartbeat loop failed", e.getCause());
        }
    }

    public boolean isStopped() {
        return stopped.isDone();
    }

    /** Stops heartbeats and waits briefly for an in-flight request to finish. Safe to call repeatedly. */
    @Override
    public void close() {
        stopped.complete(StopReason.SHUTDOWN);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.log(Level.WARNING, "Heartbeat thread did not stop within {0} s", SHUTDOWN_GRACE.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isTerminated() {
        return executor.isTerminated();
    }

    private void scheduleNext(Duration delay) {
        if (stopped.isDone()) {
            return;
        }
        try {
            executor.schedule(this::beat, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // The executor is shutting down; nothing more to do.
        }
    }

    private void beat() {
        if (stopped.isDone()) {
            return;
        }
        Duration next = interval;
        try {
            HeartbeatResult result = client.sendHeartbeat(peerId, credential, listeningPort, tlsCertificateSha256);
            switch (result.outcome()) {
                case ACCEPTED -> onAccepted(result);
                case REJECTED -> {
                    LOG.log(Level.ERROR, "The server rejected this device''s agent credential (HTTP {0}). It may "
                            + "have been revoked or rotated. Stopping heartbeats.", result.statusCode());
                    stopped.complete(StopReason.CREDENTIAL_REJECTED);
                    executor.shutdown();
                    return;
                }
                case SERVER_ERROR, UNREACHABLE -> next = onFailure(result.outcome() == HeartbeatResult.Outcome.UNREACHABLE
                        ? "server unreachable" : "server error HTTP " + result.statusCode());
            }
        } catch (RuntimeException e) {
            // A bug or unexpected client failure must not end the loop silently.
            next = onFailure("unexpected " + e.getClass().getSimpleName());
        }
        scheduleNext(next);
    }

    private void onAccepted(HeartbeatResult result) {
        if (consecutiveFailures > 0) {
            LOG.log(Level.INFO, "Heartbeats restored after {0} failed attempt(s)", consecutiveFailures);
        }
        consecutiveFailures = 0;
        String address = result.observedAddress();
        if (address != null && !address.equals(lastObservedAddress)) {
            LOG.log(Level.INFO, "Peer {0} is online; the server sees this device at {1}", peerId, address);
            if (address.startsWith("127.") || address.equals("0:0:0:0:0:0:0:1") || address.equals("::1")) {
                LOG.log(Level.WARNING, "That is a loopback address: other devices cannot reach this peer through it. "
                        + "Point --server-url at the server's LAN address instead of localhost.");
            }
            lastObservedAddress = address;
        }
    }

    private Duration onFailure(String reason) {
        consecutiveFailures++;
        Duration delay = backoff.delayFor(consecutiveFailures);
        LOG.log(Level.WARNING, "Heartbeat failed ({0}); retrying in {1} (attempt {2})",
                reason, describe(delay), consecutiveFailures);
        return delay;
    }

    private static String describe(Duration delay) {
        return delay.toSeconds() >= 1 ? delay.toSeconds() + " s" : delay.toMillis() + " ms";
    }
}
