package com.peerlink.agent.transfer;

import com.peerlink.agent.networking.ConnectionWatchdog;
import com.peerlink.agent.networking.IncomingTransferHandler;
import com.peerlink.agent.networking.TransferConnection;
import com.peerlink.agent.protocol.Message;
import com.peerlink.agent.protocol.MessageType;
import com.peerlink.agent.protocol.ProtocolChannel;
import com.peerlink.agent.protocol.ProtocolConstants;
import com.peerlink.agent.protocol.ProtocolException;
import com.peerlink.agent.protocol.TransferError;
import com.peerlink.agent.protocol.TransferMessages;
import com.peerlink.agent.protocol.TransferMessages.Offer;
import com.peerlink.agent.protocol.TransferMessages.Summary;
import com.peerlink.agent.security.TransferGrant;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sender side. Serves only the transfer the coordination server bound to the redeemed ticket,
 * and only a file the user shared from this device: the receiver names a transfer id, never a
 * file or path. Streams in bounded chunks without loading the file, stays at most
 * {@link ProtocolConstants#SEND_WINDOW_BYTES} ahead of the receiver's acknowledgements, and
 * refuses to finish if the file changed while being read.
 *
 * <p>While a transfer runs, a separate thread re-checks with the server every
 * {@code authorizationCheckInterval} that it is still authorized. Data is sent only while the
 * last confirmation is younger than twice that interval: if the server cannot be asked, sending
 * stops (fail closed). When the server answers that the transfer was cancelled or access revoked,
 * or the confirmation expires, the sender is told to stop; if it does not finish within
 * {@link #STOP_GRACE} (for instance because it is blocked writing to a receiver that stopped
 * reading) the connection is closed under it. A write that blocks longer than the idle timeout
 * closes the connection as well. Every path releases the transfer slot.
 */
public final class UploadSession implements IncomingTransferHandler, AutoCloseable {

    private static final Logger LOG = System.getLogger(UploadSession.class.getName());
    static final Duration STOP_GRACE = Duration.ofSeconds(2);
    private static final Duration MIN_CHECK_INTERVAL = Duration.ofMillis(10);

    private final ShareIndex shareIndex;
    private final TransferCoordinator coordinator;
    private final int maxConcurrentTransfers;
    private final Semaphore slots;
    private final Duration idleTimeout;
    private final Duration authorizationCheckInterval;
    private final ScheduledThreadPoolExecutor checks;
    private final AtomicLong bytesSent = new AtomicLong();
    private volatile boolean closed;

    public UploadSession(ShareIndex shareIndex, TransferCoordinator coordinator, int maxConcurrentTransfers,
                         Duration idleTimeout, Duration authorizationCheckInterval) {
        this.shareIndex = Objects.requireNonNull(shareIndex);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.maxConcurrentTransfers = maxConcurrentTransfers;
        this.slots = new Semaphore(maxConcurrentTransfers);
        this.idleTimeout = idleTimeout;
        this.authorizationCheckInterval = authorizationCheckInterval.compareTo(MIN_CHECK_INTERVAL) < 0
                ? MIN_CHECK_INTERVAL : authorizationCheckInterval;
        AtomicInteger threads = new AtomicInteger();
        // One thread per possible transfer, so a slow server answer for one never delays another's check.
        this.checks = new ScheduledThreadPoolExecutor(maxConcurrentTransfers, task -> {
            Thread thread = new Thread(task, "peerlink-upload-auth-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        checks.setRemoveOnCancelPolicy(true);
        checks.setKeepAliveTime(30, TimeUnit.SECONDS);
        checks.allowCoreThreadTimeOut(true);
    }

    /** File bytes handed to the network by all transfers so far; for diagnostics. */
    public long bytesSent() {
        return bytesSent.get();
    }

    /** Transfers currently holding a slot. */
    public int activeTransfers() {
        return maxConcurrentTransfers - slots.availablePermits();
    }

    /** Agent shutdown: running transfers end as AGENT_SHUTDOWN once the listener closes their sockets. */
    @Override
    public void close() {
        closed = true;
        checks.shutdownNow();
    }

    @Override
    public void handle(TransferConnection connection, Message request) throws IOException, ProtocolException {
        UUID requested = TransferMessages.requestedTransfer(request);
        TransferGrant grant = connection.ticket().transfer();
        ProtocolChannel channel = connection.channel();
        if (grant == null || !grant.transferId().equals(requested)) {
            LOG.log(Level.WARNING, "Peer at {0} asked for transfer {1}, which its ticket does not authorize",
                    connection.remote(), requested);
            channel.send(TransferMessages.reject(requested, TransferError.PERMISSION_DENIED));
            return;
        }
        if (!slots.tryAcquire()) {
            LOG.log(Level.WARNING, "Declined transfer {0}: already serving the maximum number of transfers", requested);
            channel.send(TransferMessages.reject(requested, TransferError.PEER_BUSY));
            coordinator.reportRejected(requested, TransferError.PEER_BUSY);
            return;
        }
        try {
            serve(connection, grant);
        } finally {
            slots.release();
        }
    }

    private void serve(TransferConnection connection, TransferGrant grant) throws IOException, ProtocolException {
        UUID transferId = grant.transferId();
        ProtocolChannel channel = connection.channel();
        SourceFile source;
        try {
            if (!ProtocolConstants.supportedChunkSize(grant.chunkSizeBytes())) {
                throw new TransferException(TransferError.PROTOCOL_ERROR, "Registered chunk size is not supported");
            }
            Optional<Path> path = shareIndex.lookup(grant.sharedFileId());
            if (path.isEmpty()) {
                throw new TransferException(TransferError.FILE_UNAVAILABLE, "File is not shared from this device");
            }
            source = SourceFile.open(path.get(), grant.fileSizeBytes());
        } catch (TransferException e) {
            LOG.log(Level.WARNING, "Declined transfer {0}: {1}", transferId, e.getMessage());
            channel.send(TransferMessages.reject(transferId, e.error()));
            coordinator.reportRejected(transferId, e.error());
            return;
        }

        Progress progress = new Progress();
        // Ticket redemption just confirmed the transfer is authorized; from here on, keep confirming.
        try (source; Authorization authorization = startAuthorization(transferId, connection.guard())) {
            try {
                // Name, size and checksum come from the server's record, not from the local file system.
                channel.send(TransferMessages.offer(new Offer(transferId, grant.fileName(), grant.fileSizeBytes(),
                        grant.sha256Checksum(), grant.chunkSizeBytes())));
                connection.input().extend(idleTimeout);
                Message reply = channel.receive();
                if (reply.type() == MessageType.TRANSFER_REJECT) {
                    LOG.log(Level.INFO, "Receiver declined transfer {0}: {1}", transferId, TransferMessages.reason(reply));
                    return;
                }
                if (reply.type() != MessageType.TRANSFER_ACCEPT) {
                    throw new ProtocolException(com.peerlink.agent.protocol.RejectReason.UNEXPECTED_MESSAGE,
                            "Expected TRANSFER_ACCEPT but got " + reply.type());
                }
                TransferMessages.transferId(reply, transferId);
                LOG.log(Level.INFO, "Sending transfer {0} ({1} bytes) to {2}", transferId, grant.fileSizeBytes(),
                        connection.remote());
                stream(connection, grant, source, authorization, progress);
            } catch (TransferException e) {
                stopped(channel, transferId, e);
            } catch (IOException e) {
                TransferError stop = authorization.stopReason();
                if (stop != null) {
                    // The connection was closed under us because the transfer had to stop.
                    stopped(channel, transferId, new TransferException(stop, describe(stop)));
                } else if (progress.endSent) {
                    // Every byte and the summary went out; only the receiver knows whether it saved the
                    // file, and it reports that itself. Reporting a failure here could contradict it.
                    LOG.log(Level.WARNING, "Transfer {0}: connection lost after all data was sent; the receiver "
                            + "reports the outcome", transferId);
                } else {
                    TransferError error = connection.guard().writeTimedOut() || e instanceof SocketTimeoutException
                            ? TransferError.TIMEOUT : closed ? TransferError.AGENT_SHUTDOWN : TransferError.CONNECTION_LOST;
                    LOG.log(Level.WARNING, "Transfer {0} stopped: {1} ({2})", transferId, error, e.getClass().getSimpleName());
                    coordinator.reportFailed(transferId, error);
                }
            } catch (ProtocolException e) {
                LOG.log(Level.WARNING, "Transfer {0}: protocol error ({1})", transferId, e.getMessage());
                sendQuietly(channel, TransferMessages.failed(transferId, TransferError.PROTOCOL_ERROR));
                coordinator.reportFailed(transferId, TransferError.PROTOCOL_ERROR);
            }
        } catch (TransferException e) {
            // Could not start the authorization checks: the agent is shutting down.
            LOG.log(Level.WARNING, "Transfer {0} stopped: {1}", transferId, e.getMessage());
            sendQuietly(channel, TransferMessages.failed(transferId, e.error()));
            coordinator.reportFailed(transferId, e.error());
        }
    }

    private void stopped(ProtocolChannel channel, UUID transferId, TransferException e) {
        LOG.log(Level.WARNING, "Transfer {0} stopped: {1}", transferId, e.getMessage());
        if (!e.reportedByPeer()) {
            sendQuietly(channel, e.error() == TransferError.PERMISSION_DENIED || e.error() == TransferError.CANCELLED
                    ? TransferMessages.cancel(transferId, e.error()) : TransferMessages.failed(transferId, e.error()));
            coordinator.reportFailed(transferId, e.error());
        }
    }

    private void stream(TransferConnection connection, TransferGrant grant, SourceFile source,
                        Authorization authorization, Progress progress)
            throws IOException, ProtocolException, TransferException {
        UUID transferId = grant.transferId();
        ProtocolChannel channel = connection.channel();
        byte[] buffer = new byte[grant.chunkSizeBytes()];
        MessageDigest digest = Checksums.newSha256();
        long size = grant.fileSizeBytes();
        long sent = 0;
        long acknowledged = 0;
        int index = 0;

        authorization.check(); // confirm once more right before the first byte
        while (sent < size) {
            authorization.require();
            int wanted = (int) Math.min(buffer.length, size - sent);
            int read = source.readFully(buffer, wanted);
            if (read < wanted) {
                throw new TransferException(TransferError.FILE_CHANGED, "The shared file became shorter while being sent");
            }
            channel.sendData(index++, buffer, 0, read);
            bytesSent.addAndGet(read);
            digest.update(buffer, 0, read);
            sent += read;
            while (sent - acknowledged > ProtocolConstants.SEND_WINDOW_BYTES) {
                acknowledged = awaitAck(connection, transferId, acknowledged, sent);
            }
        }
        source.requireUnchanged();
        String checksum = Checksums.hex(digest);
        if (!checksum.equals(grant.sha256Checksum())) {
            throw new TransferException(TransferError.FILE_CHANGED, "The shared file's content changed since it was shared");
        }
        authorization.require();
        channel.send(TransferMessages.end(new Summary(transferId, sent, checksum)));
        progress.endSent = true;

        while (true) {
            connection.input().extend(idleTimeout);
            Message message = channel.receive();
            switch (message.type()) {
                case TRANSFER_ACK -> TransferMessages.parseAck(message, transferId);
                case TRANSFER_COMPLETE -> {
                    Summary saved = TransferMessages.parseSummary(message, MessageType.TRANSFER_COMPLETE, transferId);
                    if (saved.size() != sent || !saved.sha256().equals(checksum)) {
                        throw new ProtocolException(com.peerlink.agent.protocol.RejectReason.MALFORMED,
                                "Receiver confirmed different content");
                    }
                    LOG.log(Level.INFO, "Transfer {0} delivered and verified by the receiver ({1} bytes)", transferId, sent);
                    return;
                }
                case TRANSFER_FAILED, TRANSFER_CANCEL -> throw peerStopped(message, transferId);
                default -> throw new ProtocolException(com.peerlink.agent.protocol.RejectReason.UNEXPECTED_MESSAGE,
                        "Unexpected " + message.type() + " after TRANSFER_END");
            }
        }
    }

    private long awaitAck(TransferConnection connection, UUID transferId, long acknowledged, long sent)
            throws IOException, ProtocolException, TransferException {
        connection.input().extend(idleTimeout);
        Message message = connection.channel().receive();
        if (message.type() == MessageType.TRANSFER_FAILED || message.type() == MessageType.TRANSFER_CANCEL) {
            throw peerStopped(message, transferId);
        }
        long bytes = TransferMessages.parseAck(message, transferId);
        if (bytes < acknowledged || bytes > sent) {
            throw new ProtocolException(com.peerlink.agent.protocol.RejectReason.MALFORMED, "Acknowledged bytes out of range");
        }
        return bytes;
    }

    private static TransferException peerStopped(Message message, UUID transferId) throws ProtocolException {
        TransferMessages.transferId(message, transferId);
        TransferError reason = TransferMessages.reason(message);
        return new TransferException(reason, "The receiver stopped the transfer: " + reason, true);
    }

    private static String describe(TransferError stop) {
        return switch (stop) {
            case CANCELLED -> "The transfer was cancelled";
            case PERMISSION_DENIED -> "The transfer is no longer authorized";
            case SERVER_UNAVAILABLE -> "Cannot confirm with the coordination server that the transfer is still authorized";
            default -> "Stopped: " + stop;
        };
    }

    private static void sendQuietly(ProtocolChannel channel, Message message) {
        try {
            channel.send(message);
        } catch (IOException e) {
            // The receiver is gone already.
        }
    }

    private Authorization startAuthorization(UUID transferId, ConnectionWatchdog.Guard guard) throws TransferException {
        Authorization authorization = new Authorization(transferId, guard);
        try {
            long interval = authorizationCheckInterval.toMillis();
            authorization.task = checks.scheduleWithFixedDelay(authorization::check, interval, interval,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            throw new TransferException(TransferError.AGENT_SHUTDOWN, "The agent is shutting down");
        }
        return authorization;
    }

    private static final class Progress {
        boolean endSent;
    }

    /**
     * The sender's permission to keep sending: confirmed by the server at least every
     * {@code authorizationCheckInterval} on a checker thread, and valid for twice that long.
     */
    private final class Authorization implements AutoCloseable {
        private final UUID transferId;
        private final ConnectionWatchdog.Guard guard;
        private final long leaseNanos = authorizationCheckInterval.multipliedBy(2).toNanos();
        private volatile long confirmedAt = System.nanoTime();
        private volatile TransferError stopReason;
        private volatile ScheduledFuture<?> task;
        private volatile ScheduledFuture<?> forcedStop;

        Authorization(UUID transferId, ConnectionWatchdog.Guard guard) {
            this.transferId = transferId;
            this.guard = guard;
        }

        /** Checker thread: asks the server, never blocks the sending thread. */
        void check() {
            if (stopReason != null) {
                return;
            }
            try {
                TransferState state = coordinator.status(transferId);
                if (state.cancelled()) {
                    stop(TransferError.CANCELLED);
                } else if (!state.active()) {
                    stop(TransferError.PERMISSION_DENIED);
                } else {
                    confirmedAt = System.nanoTime();
                }
            } catch (CoordinationException e) {
                if (expired()) {
                    stop(TransferError.SERVER_UNAVAILABLE);
                }
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "Unexpected error while checking transfer " + transferId, e);
                stop(TransferError.SERVER_UNAVAILABLE);
            }
        }

        /** Sending thread, before every chunk: throws unless the transfer is confirmed authorized. */
        void require() throws TransferException {
            if (stopReason == null && expired()) {
                stop(TransferError.SERVER_UNAVAILABLE);
            }
            TransferError reason = stopReason;
            if (reason != null) {
                throw new TransferException(reason, describe(reason));
            }
        }

        TransferError stopReason() {
            return stopReason;
        }

        private boolean expired() {
            return System.nanoTime() - confirmedAt > leaseNanos;
        }

        /** Tells the sending thread to stop, and closes the connection if it has not finished in time. */
        private synchronized void stop(TransferError reason) {
            if (stopReason != null) {
                return;
            }
            stopReason = reason;
            LOG.log(Level.WARNING, "Transfer {0} must stop: {1}", transferId, describe(reason));
            try {
                forcedStop = checks.schedule(guard::abort, STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                guard.abort(); // shutting down: no grace
            }
        }

        @Override
        public synchronized void close() {
            ScheduledFuture<?> periodic = task;
            if (periodic != null) {
                periodic.cancel(false);
            }
            if (forcedStop != null) {
                forcedStop.cancel(false);
            }
        }
    }
}
