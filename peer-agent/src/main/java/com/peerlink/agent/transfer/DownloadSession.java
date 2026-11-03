package com.peerlink.agent.transfer;

import com.peerlink.agent.networking.ConnectionTestResult.Outcome;
import com.peerlink.agent.networking.PeerConnector;
import com.peerlink.agent.networking.PeerConnector.ConnectionFailure;
import com.peerlink.agent.networking.PeerConnector.Established;
import com.peerlink.agent.protocol.Message;
import com.peerlink.agent.protocol.MessageType;
import com.peerlink.agent.protocol.ProtocolChannel;
import com.peerlink.agent.protocol.ProtocolChannel.Control;
import com.peerlink.agent.protocol.ProtocolChannel.Data;
import com.peerlink.agent.protocol.ProtocolChannel.Incoming;
import com.peerlink.agent.protocol.ProtocolConstants;
import com.peerlink.agent.protocol.ProtocolException;
import com.peerlink.agent.protocol.TransferError;
import com.peerlink.agent.protocol.TransferMessages;
import com.peerlink.agent.protocol.TransferMessages.Offer;
import com.peerlink.agent.protocol.TransferMessages.Summary;
import com.peerlink.agent.security.IssuedTicket;
import com.peerlink.agent.security.TicketException;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Receiver side of one transfer. Obtains a ticket bound to the transfer, connects to the sender
 * over mutually authenticated TLS, checks the sender's offer against the server's metadata,
 * receives chunks strictly in order into a private temporary file, verifies size and SHA-256,
 * confirms with the server that the transfer is still authorized (if that cannot be confirmed,
 * the file is not published), and only then publishes the file. Any failure before publication
 * deletes the partial file; interrupted transfers are not resumed.
 *
 * <p>Publication is the point of no return. Afterwards the outcome is {@link Result#COMPLETED}
 * (server confirmed) or {@link Result#SAVED_UNCONFIRMED}, whatever happens to the connection or
 * the server: a published file is never deleted, and a failure to tell the sender or the server
 * is never reported as a failed transfer. The completion report is retried (see
 * {@link CompletionReports}) until the server confirms or refuses it.
 */
public final class DownloadSession {

    /** How a download attempt ended. */
    public enum Result {
        /** Saved, verified, and confirmed by the server. */
        COMPLETED,
        /**
         * Saved and verified, but the server has not confirmed it: the report is still pending (and
         * will be retried) or the server had already recorded another outcome. The file is kept.
         */
        SAVED_UNCONFIRMED,
        /** Nothing was attempted (sender or server unavailable); the transfer stays REQUESTED. */
        RETRY_LATER,
        /** This agent's credential was rejected. */
        CREDENTIAL_REJECTED,
        REJECTED,
        FAILED
    }

    private static final Logger LOG = System.getLogger(DownloadSession.class.getName());
    /** Waits between attempts of the final authorization check; the sender waits meanwhile. */
    private static final long[] FINAL_CHECK_BACKOFF_MILLIS = {250, 500, 1000};

    private final PeerConnector connector;
    private final TransferCoordinator coordinator;
    private final Path downloadDirectory;
    private final long maxFileSizeBytes;
    private final Duration idleTimeout;
    private final Duration progressInterval;
    private final ActiveConnections connections;
    private final CompletionReports completionReports;

    public DownloadSession(PeerConnector connector, TransferCoordinator coordinator, Path downloadDirectory,
                           long maxFileSizeBytes, Duration idleTimeout, Duration progressInterval) {
        this(connector, coordinator, downloadDirectory, maxFileSizeBytes, idleTimeout, progressInterval,
                new ActiveConnections(), defaultReports(coordinator));
    }

    /** For {@link DownloadManager}: connections are registered so the manager can close them on shutdown. */
    public DownloadSession(PeerConnector connector, TransferCoordinator coordinator, Path downloadDirectory,
                           long maxFileSizeBytes, Duration idleTimeout, Duration progressInterval,
                           DownloadManager.ActiveConnectionsHandle handle) {
        this(connector, coordinator, downloadDirectory, maxFileSizeBytes, idleTimeout, progressInterval,
                handle.connections, defaultReports(coordinator));
    }

    /** As above, delivering completion reports through {@code completionReports} (retries and journal). */
    public DownloadSession(PeerConnector connector, TransferCoordinator coordinator, Path downloadDirectory,
                           long maxFileSizeBytes, Duration idleTimeout, Duration progressInterval,
                           DownloadManager.ActiveConnectionsHandle handle, CompletionReports completionReports) {
        this(connector, coordinator, downloadDirectory, maxFileSizeBytes, idleTimeout, progressInterval,
                handle.connections, completionReports);
    }

    DownloadSession(PeerConnector connector, TransferCoordinator coordinator, Path downloadDirectory,
                    long maxFileSizeBytes, Duration idleTimeout, Duration progressInterval, ActiveConnections connections,
                    CompletionReports completionReports) {
        this.connector = Objects.requireNonNull(connector);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.downloadDirectory = Objects.requireNonNull(downloadDirectory);
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.idleTimeout = idleTimeout;
        this.progressInterval = progressInterval;
        this.connections = connections;
        this.completionReports = Objects.requireNonNull(completionReports);
    }

    private static CompletionReports defaultReports(TransferCoordinator coordinator) {
        return new CompletionReports(coordinator, null, Duration.ofMillis(250), Duration.ofSeconds(10));
    }

    public Result run(PendingTransfer pending) {
        UUID transferId = pending.transferId();
        try {
            precheck(pending);
        } catch (TransferException e) {
            LOG.log(Level.WARNING, "Declining transfer {0}: {1}", transferId, e.getMessage());
            coordinator.reportRejected(transferId, e.error());
            return Result.REJECTED;
        }

        IssuedTicket ticket;
        try {
            ticket = connector.issuer().requestTicket(pending.senderPeerId(), transferId);
        } catch (TicketException e) {
            if (e.reason() == TicketException.Reason.CREDENTIAL_REJECTED) {
                return Result.CREDENTIAL_REJECTED;
            }
            LOG.log(Level.INFO, "Transfer {0} not started yet: {1}", transferId, e.getMessage());
            return Result.RETRY_LATER;
        }

        Established connection;
        try {
            connection = connector.establish(ticket);
        } catch (ConnectionFailure failure) {
            TransferError error = switch (failure.outcome()) {
                case CONNECT_FAILED, TIMEOUT -> TransferError.PEER_UNREACHABLE;
                case PEER_IDENTITY_MISMATCH -> TransferError.PERMISSION_DENIED;
                default -> TransferError.PROTOCOL_ERROR;
            };
            if (failure.outcome() == Outcome.PEER_IDENTITY_MISMATCH) {
                LOG.log(Level.WARNING, "Transfer {0}: the sender could not prove its identity: {1}", transferId,
                        failure.getMessage());
            } else {
                LOG.log(Level.WARNING, "Transfer {0}: cannot reach the sender: {1}", transferId, failure.getMessage());
            }
            coordinator.reportFailed(transferId, error);
            return Result.FAILED;
        }

        // Shutdown aborts (rather than closes) the connection: safe while this thread is blocked on it.
        AutoCloseable abort = connection::abort;
        connections.register(abort);
        try (connection) {
            return transfer(connection, pending);
        } finally {
            connections.unregister(abort);
        }
    }

    private void precheck(PendingTransfer pending) throws TransferException {
        if (pending.fileSizeBytes() > maxFileSizeBytes) {
            throw new TransferException(TransferError.FILE_TOO_LARGE, "The file (" + pending.fileSizeBytes()
                    + " bytes) is larger than this device accepts (" + maxFileSizeBytes + " bytes)");
        }
        if (!ProtocolConstants.supportedChunkSize(pending.chunkSizeBytes())) {
            throw new TransferException(TransferError.PROTOCOL_ERROR, "Unsupported chunk size");
        }
        try {
            FileNames.requireSafe(pending.fileName());
        } catch (IllegalArgumentException e) {
            throw new TransferException(TransferError.METADATA_MISMATCH, "Unsafe file name: " + e.getMessage());
        }
        DownloadTarget.requireSpace(DownloadTarget.prepareDirectory(downloadDirectory), pending.fileSizeBytes());
    }

    private Result transfer(Established connection, PendingTransfer pending) {
        UUID transferId = pending.transferId();
        ProtocolChannel channel = connection.channel();
        DownloadTarget target = null;
        boolean started = false;
        try {
            channel.send(TransferMessages.request(transferId));
            connection.extendDeadline(idleTimeout);
            Message reply = channel.receive();
            if (reply.type() == MessageType.TRANSFER_REJECT) {
                TransferMessages.transferId(reply, transferId);
                LOG.log(Level.INFO, "The sender declined transfer {0}: {1}", transferId, TransferMessages.reason(reply));
                return Result.REJECTED;
            }
            Offer offer = TransferMessages.parseOffer(reply, transferId);
            if (!matches(offer, pending)) {
                channel.send(TransferMessages.reject(transferId, TransferError.METADATA_MISMATCH));
                coordinator.reportRejected(transferId, TransferError.METADATA_MISMATCH);
                LOG.log(Level.WARNING, "Transfer {0}: the offer does not match the server's metadata", transferId);
                return Result.REJECTED;
            }
            try {
                target = DownloadTarget.create(downloadDirectory, pending.fileName(), pending.fileSizeBytes());
            } catch (TransferException e) {
                channel.send(TransferMessages.reject(transferId, e.error()));
                coordinator.reportRejected(transferId, e.error());
                return Result.REJECTED;
            }
            channel.send(TransferMessages.accept(transferId));
            started = true;
            requireStillActive(coordinator.reportProgress(transferId, 0, 0));

            receive(connection, pending, target);
            requireStillActive(finalCheck(pending));
            Path saved = target.publish();
            target = null; // published: from here on nothing may delete it or call the transfer failed
            LOG.log(Level.INFO, "Transfer {0}: saved {1} ({2} bytes, SHA-256 verified)", transferId,
                    saved.getFileName(), pending.fileSizeBytes());
            return afterPublication(channel, pending);
        } catch (TransferException e) {
            discard(target);
            LOG.log(Level.WARNING, "Transfer {0} stopped: {1}", transferId, e.getMessage());
            if (!e.reportedByPeer()) {
                sendQuietly(channel, e.error() == TransferError.CANCELLED || e.error() == TransferError.PERMISSION_DENIED
                        ? TransferMessages.cancel(transferId, e.error()) : TransferMessages.failed(transferId, e.error()));
                coordinator.reportFailed(transferId, e.error());
            }
            return Result.FAILED;
        } catch (SocketTimeoutException e) {
            return failed(target, channel, transferId, TransferError.TIMEOUT, "the sender stopped responding");
        } catch (ProtocolException e) {
            return failed(target, channel, transferId, TransferError.PROTOCOL_ERROR, e.getMessage());
        } catch (IOException e) {
            if (connection.writeTimedOut()) {
                return failed(target, channel, transferId, TransferError.TIMEOUT, "the sender stopped reading");
            }
            return failed(target, channel, transferId, connections.closed() ? TransferError.AGENT_SHUTDOWN
                    : TransferError.CONNECTION_LOST, started ? "connection lost" : "connection lost before the transfer started");
        } catch (CoordinationException e) {
            return failed(target, channel, transferId, TransferError.SERVER_UNAVAILABLE, e.getMessage());
        }
    }

    private void receive(Established connection, PendingTransfer pending, DownloadTarget target)
            throws IOException, ProtocolException, TransferException, CoordinationException {
        UUID transferId = pending.transferId();
        ProtocolChannel channel = connection.channel();
        long size = pending.fileSizeBytes();
        int chunkSize = pending.chunkSizeBytes();
        int expectedIndex = 0;
        long acknowledged = 0;
        long lastProgress = System.nanoTime();

        while (true) {
            connection.extendDeadline(idleTimeout);
            Incoming incoming = channel.receiveAny();
            if (incoming instanceof Data data) {
                long remaining = size - target.written();
                int expectedLength = (int) Math.min(chunkSize, remaining);
                if (data.index() != expectedIndex || data.bytes().length != expectedLength || remaining == 0) {
                    throw new TransferException(TransferError.PROTOCOL_ERROR, "Unexpected chunk " + data.index()
                            + " of " + data.bytes().length + " bytes (expected chunk " + expectedIndex + ")");
                }
                target.write(data.bytes());
                expectedIndex++;
                if (target.written() - acknowledged >= ProtocolConstants.ACK_INTERVAL_BYTES) {
                    acknowledged = target.written();
                    channel.send(TransferMessages.ack(transferId, acknowledged));
                }
                if (System.nanoTime() - lastProgress >= progressInterval.toNanos()) {
                    lastProgress = System.nanoTime();
                    requireStillActive(coordinator.reportProgress(transferId, target.written(), expectedIndex));
                }
                continue;
            }
            Message message = ((Control) incoming).message();
            switch (message.type()) {
                case TRANSFER_END -> {
                    Summary sent = TransferMessages.parseSummary(message, MessageType.TRANSFER_END, transferId);
                    if (sent.size() != size || !sent.sha256().equals(pending.sha256Checksum())) {
                        throw new TransferException(TransferError.PROTOCOL_ERROR, "The sender's summary does not match");
                    }
                    target.finish(pending.sha256Checksum()); // checks byte count and SHA-256
                    return;
                }
                case TRANSFER_FAILED, TRANSFER_CANCEL -> {
                    TransferMessages.transferId(message, transferId);
                    TransferError reason = TransferMessages.reason(message);
                    throw new TransferException(reason, "The sender stopped the transfer: " + reason, true);
                }
                default -> throw new TransferException(TransferError.PROTOCOL_ERROR,
                        "Unexpected " + message.type() + " during the transfer");
            }
        }
    }

    /**
     * Last authorization check before the file becomes visible. Retries a briefly unreachable server
     * a few times, then fails closed: a file whose transfer cannot be confirmed as authorized is
     * not published.
     */
    private TransferState finalCheck(PendingTransfer pending) throws CoordinationException {
        for (int attempt = 0; ; attempt++) {
            try {
                return coordinator.reportProgress(pending.transferId(), pending.fileSizeBytes(), pending.totalChunks());
            } catch (CoordinationException e) {
                if (e.credentialRejected() || attempt == FINAL_CHECK_BACKOFF_MILLIS.length) {
                    LOG.log(Level.WARNING, "Transfer {0}: cannot confirm with the server that the transfer is still "
                            + "authorized; the received file is discarded", pending.transferId());
                    throw e;
                }
            }
            try {
                Thread.sleep(FINAL_CHECK_BACKOFF_MILLIS[attempt]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CoordinationException(0, "Interrupted");
            }
        }
    }

    /**
     * The file is published. Tells the sender (best effort: it may already be gone) and the server
     * (retried until it answers definitively). Neither failure undoes or relabels the saved file.
     */
    private Result afterPublication(ProtocolChannel channel, PendingTransfer pending) {
        UUID transferId = pending.transferId();
        try {
            channel.send(TransferMessages.complete(new Summary(transferId, pending.fileSizeBytes(), pending.sha256Checksum())));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Transfer {0}: the file is saved, but the sender could not be told ({1})", transferId,
                    e.getClass().getSimpleName());
        }
        CompletionReports.Outcome reported = completionReports.deliver(transferId, pending.fileSizeBytes(),
                pending.sha256Checksum());
        return reported == CompletionReports.Outcome.CONFIRMED ? Result.COMPLETED : Result.SAVED_UNCONFIRMED;
    }

    private static void requireStillActive(TransferState state) throws TransferException {
        if (state.cancelled()) {
            throw new TransferException(TransferError.CANCELLED, "The transfer was cancelled", true);
        }
        if (!state.active()) {
            // The server has already recorded why (e.g. access revoked); just stop.
            throw new TransferException(TransferError.PERMISSION_DENIED, "The transfer is no longer authorized", true);
        }
    }

    private static boolean matches(Offer offer, PendingTransfer pending) {
        return offer.fileName().equals(pending.fileName()) && offer.size() == pending.fileSizeBytes()
                && offer.sha256().equals(pending.sha256Checksum()) && offer.chunkSize() == pending.chunkSizeBytes();
    }

    private Result failed(DownloadTarget target, ProtocolChannel channel, UUID transferId, TransferError error,
                          String detail) {
        discard(target);
        LOG.log(Level.WARNING, "Transfer {0} failed: {1}", transferId, detail);
        sendQuietly(channel, TransferMessages.failed(transferId, error));
        coordinator.reportFailed(transferId, error);
        return Result.FAILED;
    }

    private static void discard(DownloadTarget target) {
        if (target != null) {
            target.discard();
        }
    }

    private static void sendQuietly(ProtocolChannel channel, Message message) {
        try {
            channel.send(message);
        } catch (IOException e) {
            // The sender is gone already.
        }
    }
}
