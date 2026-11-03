package com.peerlink.agent.networking;

import com.peerlink.agent.protocol.FrameIO;
import com.peerlink.agent.protocol.Handshake;
import com.peerlink.agent.protocol.MessageCodec;
import com.peerlink.agent.protocol.ProtocolConstants;
import com.peerlink.agent.protocol.RejectReason;
import com.peerlink.agent.security.PeerTls;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSocket;

/**
 * Accepts incoming peer connections (all of them TLS, see {@link InboundConnectionHandler}). At most
 * {@code maxConnections} are served at once, each on a thread of a fixed pool; further connections
 * get a REJECT BUSY and are closed. A failing
 * connection never affects the accept loop. {@link #close()} releases the port and all threads.
 */
public final class PeerListener implements AutoCloseable {

    /**
     * @param bindAddress loopback for same-machine use; a LAN address or 0.0.0.0 (all interfaces)
     *                    for connections from other devices
     * @param port        0 picks a free port (tests)
     */
    public record Settings(InetAddress bindAddress, int port, int maxConnections) {
        public Settings {
            Objects.requireNonNull(bindAddress, "bindAddress");
            if (port < 0 || port > 65535 || maxConnections < 1) {
                throw new IllegalArgumentException("Invalid listener settings");
            }
        }
    }

    private static final Logger LOG = System.getLogger(PeerListener.class.getName());
    private static final int BACKLOG = 50;
    private static final int PENDING_REJECTIONS = 16;
    private static final Duration REJECTION_DRAIN = Duration.ofMillis(500);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final Settings settings;
    private final InboundConnectionHandler handler;
    private final PeerTls tls;
    private final Semaphore permits;
    private final ExecutorService workers;
    private final ThreadPoolExecutor rejector;
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket serverSocket;
    private volatile boolean closed;
    private Thread acceptThread;

    /** @param tls used to tell over-limit clients they are refused (all peer traffic is TLS) */
    public PeerListener(Settings settings, InboundConnectionHandler handler, PeerTls tls) {
        this.settings = Objects.requireNonNull(settings);
        this.handler = Objects.requireNonNull(handler);
        this.tls = Objects.requireNonNull(tls);
        this.permits = new Semaphore(settings.maxConnections());
        this.workers = Executors.newFixedThreadPool(settings.maxConnections(), named("peerlink-peer-conn"));
        // Busy rejections are written on their own small thread so the accept loop never blocks.
        this.rejector = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(PENDING_REJECTIONS), named("peerlink-peer-reject"),
                (task, executor) -> { /* only after shutdown; the socket is closed with the listener */ });
    }

    /** Binds the port and starts accepting. */
    public void start() throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(settings.bindAddress(), settings.port()), BACKLOG);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        serverSocket = socket;
        acceptThread = Thread.ofPlatform().name("peerlink-listener").daemon(true).start(this::acceptLoop);
        LOG.log(Level.INFO, "Listening for peer connections on {0}:{1,number,#} (max {2} at once)",
                settings.bindAddress().getHostAddress(), port(), settings.maxConnections());
    }

    /** The bound port (useful when started with port 0). */
    public int port() {
        return serverSocket.getLocalPort();
    }

    /** Connections currently being served. */
    public int activeConnections() {
        return settings.maxConnections() - permits.availablePermits();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (!closed) {
                    LOG.log(Level.WARNING, "Failed to accept a connection: {0}", e.getMessage());
                }
                continue;
            }
            dispatch(socket);
        }
    }

    private void dispatch(Socket socket) {
        if (!permits.tryAcquire()) {
            LOG.log(Level.WARNING, "Rejected connection from {0}: connection limit ({1}) reached",
                    socket.getRemoteSocketAddress(), settings.maxConnections());
            if (rejector.getQueue().remainingCapacity() == 0) {
                closeQuietly(socket); // too many pending rejections: just drop it
            } else {
                rejector.execute(() -> rejectBusy(tls, socket));
            }
            return;
        }
        openSockets.add(socket);
        try {
            workers.execute(() -> serve(socket));
        } catch (RejectedExecutionException e) {
            release(socket); // shutting down
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            handler.handle(socket);
        } catch (SocketException e) {
            LOG.log(Level.INFO, "Connection from {0} failed: {1}", socket.getRemoteSocketAddress(), e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Unexpected error in peer connection worker", e);
        } finally {
            release(socket);
        }
    }

    private void release(Socket socket) {
        closeQuietly(socket);
        openSockets.remove(socket);
        permits.release();
    }

    /**
     * Tells the client we are busy (over TLS, like all peer traffic), then drains its pending bytes
     * so closing does not reset the reply. No ticket is read or redeemed.
     */
    private static void rejectBusy(PeerTls tls, Socket accepted) {
        try (accepted) {
            accepted.setSoTimeout((int) REJECTION_DRAIN.multipliedBy(4).toMillis());
            SSLSocket socket = tls.serverSide(accepted);
            socket.startHandshake();
            OutputStream out = socket.getOutputStream();
            FrameIO.writeControl(out, MessageCodec.encode(Handshake.reject(RejectReason.BUSY)));
            socket.setSoTimeout((int) REJECTION_DRAIN.toMillis());
            InputStream in = socket.getInputStream();
            byte[] discard = new byte[ProtocolConstants.MAX_FRAME_BYTES];
            int total = 0;
            while (total < 2 * ProtocolConstants.MAX_FRAME_BYTES) {
                int read = in.read(discard);
                if (read < 0) {
                    break;
                }
                total += read;
            }
        } catch (IOException e) {
            // Best effort: the connection is being refused anyway.
        }
    }

    /** Stops accepting, closes every open connection and waits briefly for workers to finish. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeQuietly(serverSocket);
        openSockets.forEach(PeerListener::closeQuietly);
        workers.shutdownNow();
        rejector.shutdownNow();
        handler.close();
        try {
            if (!workers.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.log(Level.WARNING, "Some peer connection workers did not stop in time");
            }
            if (acceptThread != null) {
                acceptThread.join(SHUTDOWN_GRACE.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOG.log(Level.INFO, "Peer listener stopped");
    }

    public boolean isClosed() {
        return closed && (acceptThread == null || !acceptThread.isAlive()) && workers.isTerminated();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            // Closing during shutdown; nothing useful to report.
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
