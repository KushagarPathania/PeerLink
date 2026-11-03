package com.peerlink.agent.networking;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Deadlines that a blocked thread cannot enforce itself. Java sockets have no write timeout
 * ({@code SO_TIMEOUT} only limits reads), so a peer that stops reading can block a writer
 * forever. A {@link Guard} records when each write starts; a single watchdog thread closes the
 * plain TCP socket of any connection whose write has not returned within its deadline, which
 * makes the blocked write fail immediately. Other threads use {@link Guard#abort()} the same way
 * (cancellation, revocation, shutdown).
 *
 * <p>The plain socket is closed, never the TLS socket layered on it: closing a TLS socket sends
 * close_notify and may wait for the lock the blocked writer holds. Tasks are cheap, never block,
 * and are removed from the queue as soon as they are cancelled, so finished connections leave
 * nothing behind.
 */
public final class ConnectionWatchdog implements AutoCloseable {

    private static final Duration MAX_CHECK_INTERVAL = Duration.ofMillis(250);
    private static final Duration MIN_CHECK_INTERVAL = Duration.ofMillis(10);

    private final ScheduledThreadPoolExecutor executor;

    public ConnectionWatchdog(String threadName) {
        executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, threadName);
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setKeepAliveTime(30, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
    }

    /** Runs {@code task} once after {@code delay}; it must not block. */
    public ScheduledFuture<?> schedule(Runnable task, Duration delay) {
        return executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Starts watching the writes of a connection.
     *
     * @param plainSocket  the TCP socket (under TLS, if any); closed when a write overruns
     * @param writeTimeout longest time one write may block before the connection is closed
     */
    public Guard guard(Socket plainSocket, Duration writeTimeout) {
        return new Guard(plainSocket, writeTimeout);
    }

    /** Tasks still queued (finished and cancelled ones are removed); for tests. */
    int queuedTasks() {
        return executor.getQueue().size();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /** Write deadline and abort switch of one connection. Close it when the connection ends. */
    public final class Guard implements AutoCloseable {

        private final Socket socket;
        private final long writeTimeoutNanos;
        private final ScheduledFuture<?> check;
        private volatile boolean writing;
        private volatile long writeStartedNanos;
        private volatile boolean writeTimedOut;
        private volatile boolean aborted;

        private Guard(Socket socket, Duration writeTimeout) {
            this.socket = socket;
            this.writeTimeoutNanos = writeTimeout.toNanos();
            long interval = Math.clamp(writeTimeout.toMillis() / 4, MIN_CHECK_INTERVAL.toMillis(),
                    MAX_CHECK_INTERVAL.toMillis());
            this.check = executor.scheduleWithFixedDelay(this::checkWrite, interval, interval, TimeUnit.MILLISECONDS);
        }

        /** Wraps the connection's output so every write is covered by the deadline. */
        public OutputStream output(OutputStream out) {
            return new FilterOutputStream(out) {
                @Override
                public void write(int b) throws IOException {
                    begin();
                    try {
                        out.write(b);
                    } finally {
                        writing = false;
                    }
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    begin();
                    try {
                        out.write(bytes, offset, length);
                    } finally {
                        writing = false;
                    }
                }

                @Override
                public void flush() throws IOException {
                    begin();
                    try {
                        out.flush();
                    } finally {
                        writing = false;
                    }
                }
            };
        }

        /** Closes the connection now, unblocking any thread reading or writing it. Idempotent. */
        public void abort() {
            aborted = true;
            try {
                socket.close();
            } catch (IOException e) {
                // Already closed.
            }
        }

        /** Whether the connection was closed because a write did not finish in time. */
        public boolean writeTimedOut() {
            return writeTimedOut;
        }

        public boolean aborted() {
            return aborted;
        }

        /** Stops watching (the connection is over); the periodic check is removed from the queue. */
        @Override
        public void close() {
            check.cancel(false);
        }

        private void begin() throws IOException {
            if (aborted) {
                throw new IOException("Connection aborted");
            }
            writeStartedNanos = System.nanoTime();
            writing = true;
        }

        private void checkWrite() {
            if (writing && System.nanoTime() - writeStartedNanos > writeTimeoutNanos) {
                writeTimedOut = true;
                abort();
            }
        }
    }
}
