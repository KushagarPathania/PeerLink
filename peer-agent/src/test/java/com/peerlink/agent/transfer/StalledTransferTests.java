package com.peerlink.agent.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.peerlink.agent.networking.InboundConnectionHandler;
import com.peerlink.agent.networking.PeerConnector;
import com.peerlink.agent.networking.PeerListener;
import com.peerlink.agent.protocol.Handshake;
import com.peerlink.agent.protocol.Message;
import com.peerlink.agent.protocol.MessageType;
import com.peerlink.agent.protocol.ProtocolConstants;
import com.peerlink.agent.protocol.TransferMessages;
import com.peerlink.agent.security.IssuedTicket;
import com.peerlink.agent.security.PeerTls;
import com.peerlink.agent.security.TlsIdentity;
import com.peerlink.agent.security.TransferGrant;
import com.peerlink.agent.testing.FakeCoordinator;
import com.peerlink.agent.testing.InMemoryTicketAuthority;
import com.peerlink.agent.testing.RawPeer;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A receiver that accepts a transfer and then stops reading, over a real TLS connection on
 * loopback. Its TCP receive buffer is tiny, so the sender ends up blocked inside a socket write.
 * Some systems (macOS) keep growing the sender's send buffer for a while, so the tests first wait
 * until the sender thread sits in a write and has sent nothing for a while: a stall that, without
 * a write deadline or abort, would last forever. Every way of ending such a transfer must then
 * unblock the sender within a bounded time and free its slot for the next transfer.
 */
@Timeout(60)
class StalledTransferTests {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final int CHUNK = 64 * 1024;
    /** Generous for a healthy transfer, so in these tests only the mechanism under test can fire. */
    private static final Duration LONG = Duration.ofSeconds(30);
    /** The sender must give up well within this. */
    private static final Duration BOUND = UploadSession.STOP_GRACE.plusSeconds(5);
    /**
     * How long a stalled sender must show no progress before the test treats the stall as
     * permanent: longer than the 5 s minimum TCP persist (zero-window probe) interval, after which
     * macOS may still accept one more segment.
     */
    private static final Duration NO_PROGRESS = Duration.ofSeconds(6);
    /** Upper limit for the sender's send buffer to stop growing (macOS: up to 4 MiB, in steps). */
    private static final Duration STALL_SETTLES = Duration.ofSeconds(30);

    @TempDir
    Path temp;

    private final UUID sender = UUID.randomUUID();
    private final UUID receiver = UUID.randomUUID();
    private final PeerTls senderTls = new PeerTls(TlsIdentity.generate("sender"));
    private final PeerTls receiverTls = new PeerTls(TlsIdentity.generate("receiver"));
    private final InMemoryTicketAuthority authority = new InMemoryTicketAuthority();
    private final FakeCoordinator senderServer = new FakeCoordinator();
    private ShareIndex shareIndex;
    private UploadSession uploads;
    private PeerListener listener;

    @AfterEach
    void stop() {
        if (uploads != null) {
            uploads.close();
        }
        if (listener != null) {
            listener.close();
        }
    }

    @Test
    void aWriteThatBlocksLongerThanTheIdleTimeoutClosesTheConnectionAndFreesTheSlot() throws Exception {
        startSender(Duration.ofSeconds(1), LONG);
        PendingTransfer pending = share(randomFile("big.bin", 32L * 1024 * 1024));

        try (RawPeer stalled = stalledReceiver(pending)) {
            // The deadline fires as soon as one write blocks for 1 s, typically before a permanent stall.
            await(StalledTransferTests::senderBlockedInWrite, Duration.ofSeconds(10));

            await(() -> senderServer.reported("failed:"), STALL_SETTLES.plus(BOUND));

            assertThat(senderServer.reports).containsExactly("failed:TIMEOUT");
            await(() -> uploads.activeTransfers() == 0, BOUND);
            assertThat(stalled.closedByRemote()).isTrue();
        }
        assertNextTransferSucceeds();
    }

    @Test
    void cancellationUnblocksASenderStuckInAWrite() throws Exception {
        startSender(LONG, Duration.ofMillis(100));
        PendingTransfer pending = share(randomFile("big.bin", 32L * 1024 * 1024));

        try (RawPeer stalled = stalledReceiver(pending)) {
            awaitSenderBlockedInWrite();
            long start = System.nanoTime();
            senderServer.status = () -> new TransferState("CANCELLED", false);

            await(() -> uploads.activeTransfers() == 0, BOUND);

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(BOUND);
            // CANCELLED is the transfer's status (set by the user), not an error the agent reports.
            assertThat(senderServer.reports).noneMatch(report -> report.startsWith("failed:"));
            assertThat(stalled.closedByRemote()).isTrue();
        }
        senderServer.status = () -> new TransferState("IN_PROGRESS", true);
        assertNextTransferSucceeds();
    }

    @Test
    void cancellationUnblocksASenderWaitingForAcknowledgements() throws Exception {
        startSender(LONG, Duration.ofMillis(100));
        PendingTransfer pending = share(randomFile("big.bin", 32L * 1024 * 1024));

        try (RawPeer silent = new RawPeer(senderAddress(), receiverTls, senderTls.identity().fingerprint())) {
            startRawTransfer(silent, pending);
            silent.send(TransferMessages.accept(pending.transferId()));
            // Reads the whole send window but never acknowledges: the sender blocks reading (30 s deadline).
            assertThat(silent.readData(ProtocolConstants.SEND_WINDOW_BYTES)).isNull();
            await(StalledTransferTests::senderAwaitingAcknowledgement, Duration.ofSeconds(10));
            long start = System.nanoTime();
            senderServer.status = () -> new TransferState("CANCELLED", false);

            await(() -> uploads.activeTransfers() == 0, BOUND);

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(BOUND);
            assertThat(silent.dataBytesSkipped).isLessThan(ProtocolConstants.SEND_WINDOW_BYTES + 2L * CHUNK);
        }
        senderServer.status = () -> new TransferState("IN_PROGRESS", true);
        assertNextTransferSucceeds();
    }

    @Test
    void revokedAccessUnblocksASenderStuckInAWrite() throws Exception {
        startSender(LONG, Duration.ofMillis(100));
        PendingTransfer pending = share(randomFile("big.bin", 32L * 1024 * 1024));

        try (RawPeer stalled = stalledReceiver(pending)) {
            awaitSenderBlockedInWrite();
            senderServer.status = () -> new TransferState("IN_PROGRESS", false);

            await(() -> senderServer.reported("failed:"), BOUND);

            assertThat(senderServer.reports).containsExactly("failed:PERMISSION_DENIED");
            await(() -> uploads.activeTransfers() == 0, BOUND);
        }
        senderServer.status = () -> new TransferState("IN_PROGRESS", true);
        assertNextTransferSucceeds();
    }

    @Test
    void unreachableAuthorizationServiceStopsTheSenderInsteadOfSendingOn() throws Exception {
        startSender(LONG, Duration.ofMillis(100));
        PendingTransfer pending = share(randomFile("big.bin", 32L * 1024 * 1024));

        try (RawPeer stalled = stalledReceiver(pending)) {
            awaitSenderBlockedInWrite();
            senderServer.unreachable = true; // authorization can no longer be confirmed

            await(() -> senderServer.reported("failed:"), BOUND);

            assertThat(senderServer.reports).containsExactly("failed:SERVER_UNAVAILABLE");
            await(() -> uploads.activeTransfers() == 0, BOUND);
        }
        senderServer.unreachable = false;
        assertNextTransferSucceeds();
    }

    @Test
    void unreachableAuthorizationServiceStopsDataEvenWhenTheReceiverKeepsReading() throws Exception {
        startSender(LONG, Duration.ofMillis(100));
        PendingTransfer pending = share(randomFile("big.bin", 32L * 1024 * 1024));
        senderServer.unreachable = true; // from the start: only the ticket redemption vouched for it

        try (RawPeer raw = new RawPeer(senderAddress(), receiverTls, senderTls.identity().fingerprint())) {
            startRawTransfer(raw, pending);
            raw.send(TransferMessages.accept(pending.transferId()));
            // A diligent (if unhurried) receiver that reads and acknowledges everything, so only the
            // expired authorization can stop the sender.
            Message stop = null;
            for (long next = 1024 * 1024; stop == null && next <= pending.fileSizeBytes(); next += 1024 * 1024) {
                stop = raw.readData(next);
                if (stop == null) {
                    raw.send(TransferMessages.ack(pending.transferId(), raw.dataBytesSkipped));
                    Thread.sleep(50);
                }
            }
            assertThat(stop).isNotNull();
            assertThat(stop.type()).isEqualTo(MessageType.TRANSFER_FAILED);
            assertThat(stop.optionalField("reason")).isEqualTo("SERVER_UNAVAILABLE");
            assertThat(raw.dataBytesSkipped).isLessThan(pending.fileSizeBytes());
        } catch (java.io.IOException acceptable) {
            // The forced close may win the race against the FAILED message.
        }
        await(() -> senderServer.reported("failed:"), BOUND);
        assertThat(senderServer.reports).containsExactly("failed:SERVER_UNAVAILABLE");
    }

    @Test
    void shuttingDownUnblocksASenderStuckInAWrite() throws Exception {
        startSender(LONG, LONG);
        PendingTransfer pending = share(randomFile("big.bin", 32L * 1024 * 1024));

        try (RawPeer stalled = stalledReceiver(pending)) {
            awaitSenderBlockedInWrite();
            long start = System.nanoTime();

            uploads.close();
            listener.close();

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(BOUND);
            assertThat(listener.isClosed()).isTrue();
            assertThat(uploads.activeTransfers()).isZero();
            assertThat(senderServer.reports).containsExactly("failed:AGENT_SHUTDOWN");
        }
    }

    @Test
    void aSlowReceiverThatKeepsReadingIsNotCutOff() throws Exception {
        // Write deadline 1 s, but the receiver drains a little at a time: progress, not a stall.
        startSender(Duration.ofSeconds(1), LONG);
        PendingTransfer pending = share(randomFile("slow.bin", 3L * 1024 * 1024));

        try (RawPeer slow = new RawPeer(senderAddress(), receiverTls, senderTls.identity().fingerprint(), 8 * 1024)) {
            startRawTransfer(slow, pending);
            slow.send(TransferMessages.accept(pending.transferId()));
            long start = System.nanoTime();
            for (long read = 256 * 1024; read <= pending.fileSizeBytes(); read += 256 * 1024) {
                slow.readData(read);
                Thread.sleep(150);
            }
            var end = slow.receiveControlSkippingData();
            assertThat(end.type()).isEqualTo(MessageType.TRANSFER_END);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThan(Duration.ofSeconds(1));
            assertThat(senderServer.reports).isEmpty();
        }
    }

    // --- Harness ----------------------------------------------------------------------

    private void startSender(Duration idleTimeout, Duration authorizationCheckInterval) throws Exception {
        shareIndex = new ShareIndex(temp.resolve("agent/shared-files.properties"));
        uploads = new UploadSession(shareIndex, senderServer, 1, idleTimeout, authorizationCheckInterval);
        listener = new PeerListener(new PeerListener.Settings(LOOPBACK, 0, 4),
                new InboundConnectionHandler(sender, authority.verifierFor(sender), senderTls, uploads,
                        Duration.ofSeconds(5), idleTimeout), senderTls);
        listener.start();
        authority.register(sender, senderAddress(), senderTls.identity().fingerprint());
    }

    /** Accepts the transfer, then never reads again; its receive buffer is a few KiB. */
    private RawPeer stalledReceiver(PendingTransfer pending) throws Exception {
        RawPeer raw = new RawPeer(senderAddress(), receiverTls, senderTls.identity().fingerprint(), 4 * 1024);
        startRawTransfer(raw, pending);
        raw.send(TransferMessages.accept(pending.transferId()));
        return raw;
    }

    /** Waits until the sender's thread is inside a socket write and has made no progress for {@link #NO_PROGRESS}. */
    private void awaitSenderBlockedInWrite() throws Exception {
        long deadline = System.nanoTime() + STALL_SETTLES.toNanos();
        long sent = -1;
        long unchangedSince = System.nanoTime();
        while (System.nanoTime() < deadline) {
            long now = uploads.bytesSent();
            if (now != sent || !senderBlockedInWrite()) {
                sent = now;
                unchangedSince = System.nanoTime();
            } else if (System.nanoTime() - unchangedSince > NO_PROGRESS.toNanos()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("The sender never stalled in a socket write (sent " + sent + " bytes)");
    }

    private static boolean senderAwaitingAcknowledgement() {
        return Thread.getAllStackTraces().entrySet().stream()
                .filter(thread -> thread.getKey().getName().startsWith("peerlink-peer-conn"))
                .anyMatch(thread -> Arrays.stream(thread.getValue()).anyMatch(frame ->
                        frame.getClassName().endsWith("UploadSession") && frame.getMethodName().equals("awaitAck")));
    }

    private static boolean senderBlockedInWrite() {
        for (Map.Entry<Thread, StackTraceElement[]> thread : Thread.getAllStackTraces().entrySet()) {
            if (!thread.getKey().getName().startsWith("peerlink-peer-conn")) {
                continue;
            }
            StackTraceElement[] stack = thread.getValue();
            boolean streaming = Arrays.stream(stack).anyMatch(frame -> frame.getClassName().endsWith("UploadSession")
                    && frame.getMethodName().equals("stream"));
            boolean writing = Arrays.stream(stack).anyMatch(frame -> frame.getClassName().contains("SSLSocketImpl$AppOutputStream")
                    && frame.getMethodName().equals("write"));
            if (streaming && writing) {
                return true;
            }
        }
        return false;
    }

    /** The slot is free again: a normal transfer to a well-behaved receiver completes. */
    private void assertNextTransferSucceeds() throws Exception {
        senderServer.reports.clear();
        Path source = randomFile("next.bin", 300_000);
        PendingTransfer next = share(source);
        Path downloads = temp.resolve("downloads");
        PeerConnector connector = new PeerConnector(receiver, authority.issuerFor(receiver,
                receiverTls.identity().fingerprint()), receiverTls, PeerConnector.Settings.defaults());
        FakeCoordinator receiverServer = new FakeCoordinator();

        DownloadSession.Result result = new DownloadSession(connector, receiverServer, downloads, Long.MAX_VALUE,
                Duration.ofSeconds(5), Duration.ofMillis(50)).run(next);

        assertThat(result).isEqualTo(DownloadSession.Result.COMPLETED);
        assertThat(Files.mismatch(source, downloads.resolve("next.bin"))).isEqualTo(-1);
        assertThat(senderServer.reports).isEmpty();
    }

    private void startRawTransfer(RawPeer raw, PendingTransfer pending) throws Exception {
        TransferGrant grant = new TransferGrant(pending.transferId(), pending.sharedFileId(), pending.fileName(),
                pending.fileSizeBytes(), pending.sha256Checksum(), CHUNK);
        IssuedTicket ticket = authority.issueTicket(receiver, receiverTls.identity().fingerprint(), sender,
                senderAddress(), senderTls.identity().fingerprint(), grant);
        raw.magic().send(Handshake.hello(receiver, ticket.ticket()));
        assertThat(raw.receive().type()).isEqualTo(MessageType.HELLO_ACK);
        raw.send(TransferMessages.request(pending.transferId()));
        assertThat(raw.receive().type()).isEqualTo(MessageType.TRANSFER_OFFER);
    }

    private InetSocketAddress senderAddress() {
        return new InetSocketAddress(LOOPBACK, listener.port());
    }

    private PendingTransfer share(Path file) throws Exception {
        UUID fileId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        shareIndex.put(fileId, file);
        long size = Files.size(file);
        String checksum = sha256(file);
        String name = file.getFileName().toString();
        authority.grant(new TransferGrant(transferId, fileId, name, size, checksum, CHUNK));
        return new PendingTransfer(transferId, fileId, sender, name, size, checksum, CHUNK,
                (int) ((size + CHUNK - 1) / CHUNK));
    }

    private Path randomFile(String name, long size) throws Exception {
        Path file = temp.resolve("shared").resolve(name);
        Files.createDirectories(file.getParent());
        Random random = new Random(size);
        byte[] buffer = new byte[ProtocolConstants.MAX_DATA_CHUNK_BYTES];
        try (var out = Files.newOutputStream(file)) {
            for (long written = 0; written < size; written += buffer.length) {
                random.nextBytes(buffer);
                out.write(buffer, 0, (int) Math.min(buffer.length, size - written));
            }
        }
        return file;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Passes as soon as the condition is seen true once (it may be transient, like "inside a write"). */
    private static void await(BooleanSupplier condition, Duration within) throws InterruptedException {
        long deadline = System.nanoTime() + within.toNanos();
        boolean met;
        while (!(met = condition.getAsBoolean()) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(met).as("condition within %s", within).isTrue();
    }
}
