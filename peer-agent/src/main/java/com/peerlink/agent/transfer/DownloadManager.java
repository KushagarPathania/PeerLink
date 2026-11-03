package com.peerlink.agent.transfer;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Picks up transfers the user requested to this device and runs at most {@code maxConcurrent} of
 * them at a time, on its own threads (heartbeats are never delayed). Polls the server every
 * {@code pollInterval}; there is no queue beyond the server's own list.
 */
public final class DownloadManager implements AutoCloseable {

    private static final Logger LOG = System.getLogger(DownloadManager.class.getName());
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final TransferCoordinator coordinator;
    private final DownloadSessionFactory sessions;
    private final Semaphore slots;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final ActiveConnections connections = new ActiveConnections();
    private final ScheduledExecutorService poller;
    private final ExecutorService workers;
    private final Duration pollInterval;
    private final CompletionReports completionReports;
    private volatile boolean serverUnreachableLogged;

    /** Builds a download session that registers its connection with the manager. */
    @FunctionalInterface
    public interface DownloadSessionFactory {
        DownloadSession create(ActiveConnectionsHandle connections);
    }

    /** Opaque handle passed to {@link DownloadSession}; lets shutdown close running transfers. */
    public static final class ActiveConnectionsHandle {
        final ActiveConnections connections;

        ActiveConnectionsHandle(ActiveConnections connections) {
            this.connections = connections;
        }
    }

    public DownloadManager(TransferCoordinator coordinator, DownloadSessionFactory sessions, int maxConcurrent,
                           Duration pollInterval) {
        this(coordinator, sessions, maxConcurrent, pollInterval, null);
    }

    /** @param completionReports undelivered completion reports to retry on every poll, or {@code null} */
    public DownloadManager(TransferCoordinator coordinator, DownloadSessionFactory sessions, int maxConcurrent,
                           Duration pollInterval, CompletionReports completionReports) {
        this.completionReports = completionReports;
        this.coordinator = coordinator;
        this.sessions = sessions;
        this.slots = new Semaphore(maxConcurrent);
        this.pollInterval = pollInterval;
        this.poller = Executors.newSingleThreadScheduledExecutor(named("peerlink-transfer-poll"));
        this.workers = Executors.newFixedThreadPool(maxConcurrent, named("peerlink-download"));
    }

    public void start() {
        poller.scheduleWithFixedDelay(this::poll, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * One polling round: first retry completion reports the server has not confirmed yet (also
     * those left from before a restart), then start as many pending transfers as there are free
     * slots. Never throws.
     */
    void poll() {
        if (completionReports != null) {
            completionReports.retryPending();
        }
        try {
            while (slots.tryAcquire()) {
                Optional<PendingTransfer> next = coordinator.nextTransfer();
                serverUnreachableLogged = false;
                if (next.isEmpty() || !inFlight.add(next.get().transferId())) {
                    slots.release();
                    return;
                }
                PendingTransfer pending = next.get();
                LOG.log(Level.INFO, "Starting download of \"{0}\" ({1} bytes), transfer {2}",
                        pending.fileName().replaceAll("\\p{Cntrl}", "?"), pending.fileSizeBytes(), pending.transferId());
                workers.execute(() -> run(pending));
            }
        } catch (CoordinationException e) {
            slots.release();
            if (!serverUnreachableLogged) {
                LOG.log(Level.WARNING, "Cannot check for requested transfers: {0}", e.getMessage());
                serverUnreachableLogged = true;
            }
        } catch (RuntimeException e) {
            slots.release();
            LOG.log(Level.ERROR, "Unexpected error while checking for transfers", e);
        }
    }

    private void run(PendingTransfer pending) {
        try {
            DownloadSession.Result result = sessions.create(new ActiveConnectionsHandle(connections)).run(pending);
            if (result == DownloadSession.Result.CREDENTIAL_REJECTED) {
                LOG.log(Level.ERROR, "The server rejected this device's credential; no further downloads");
                poller.shutdown();
            }
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Unexpected error in transfer " + pending.transferId(), e);
        } finally {
            inFlight.remove(pending.transferId());
            slots.release();
        }
    }

    public int activeDownloads() {
        return inFlight.size();
    }

    @Override
    public void close() {
        poller.shutdownNow();
        connections.closeAll();   // unblocks workers waiting on sockets; their partial files are discarded
        workers.shutdownNow();
        try {
            workers.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static java.util.concurrent.ThreadFactory named(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
