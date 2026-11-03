package com.peerlink.agent.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.peerlink.agent.networking.IncomingTransferHandler;
import com.peerlink.agent.networking.InboundConnectionHandler;
import com.peerlink.agent.networking.PeerConnector;
import com.peerlink.agent.networking.PeerListener;
import com.peerlink.agent.protocol.Handshake;
import com.peerlink.agent.protocol.Message;
import com.peerlink.agent.protocol.MessageType;
import com.peerlink.agent.protocol.ProtocolChannel;
import com.peerlink.agent.protocol.ProtocolConstants;
import com.peerlink.agent.protocol.TransferError;
import com.peerlink.agent.protocol.TransferMessages;
import com.peerlink.agent.protocol.TransferMessages.Offer;
import com.peerlink.agent.protocol.TransferMessages.Summary;
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
import java.util.HexFormat;
import java.util.Random;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two agents on loopback: a sender (listener + UploadSession) and a receiver (DownloadSession),
 * talking the real protocol over mutually authenticated TLS. The coordination server is replaced
 * by in-memory fakes, which by construction never see file content.
 */
class FileTransferIntegrationTests {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final int CHUNK = 64 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path temp;

    private final UUID sender = UUID.randomUUID();
    private final UUID receiver = UUID.randomUUID();
    private final PeerTls senderTls = new PeerTls(TlsIdentity.generate("sender"));
    private final PeerTls receiverTls = new PeerTls(TlsIdentity.generate("receiver"));
    private final InMemoryTicketAuthority authority = new InMemoryTicketAuthority();
    private final FakeCoordinator senderServer = new FakeCoordinator();
    private final FakeCoordinator receiverServer = new FakeCoordinator();
    private ShareIndex shareIndex;
    private Path downloads;
    private PeerListener listener;

    @BeforeEach
    void setUp() throws Exception {
        shareIndex = new ShareIndex(temp.resolve("agent/shared-files.properties"));
        downloads = temp.resolve("downloads");
        startSender(new UploadSession(shareIndex, senderServer, 2, TIMEOUT, Duration.ofSeconds(5)));
    }

    @AfterEach
    void stopSender() {
        listener.close();
    }

    // --- Successful transfers -------------------------------------------------------

    @Test
    void transfersABinaryFileByteForByteAndReportsOnlyMetadata() throws Exception {
        Path source = randomFile("photo.raw", 1_500_000);
        PendingTransfer pending = share(source);

        DownloadSession.Result result = receiverSession(Long.MAX_VALUE).run(pending);

        assertThat(result).isEqualTo(DownloadSession.Result.COMPLETED);
        assertThat(Files.mismatch(source, downloads.resolve("photo.raw"))).isEqualTo(-1);
        assertThat(downloadEntries()).containsExactly("photo.raw");
        assertThat(receiverServer.reports).contains("completed:1500000:" + pending.sha256Checksum());
        assertThat(receiverServer.reports).anyMatch(report -> report.startsWith("progress:"));
        assertThat(senderServer.reports).isEmpty();
    }

    @Test
    void transfersAnEmptyFile() throws Exception {
        Path source = Files.createFile(temp.resolve("empty.txt"));

        assertThat(receiverSession(Long.MAX_VALUE).run(share(source))).isEqualTo(DownloadSession.Result.COMPLETED);
        assertThat(downloads.resolve("empty.txt")).isEmptyFile();
    }

    @Test
    void streamsALargeFileThroughTheSendWindow() throws Exception {
        // 48 MiB: several times the 8 MiB send window, so acknowledgements must flow.
        Path source = randomFile("video.bin", 48L * 1024 * 1024 + 123);
        PendingTransfer pending = share(source);

        assertThat(receiverSession(Long.MAX_VALUE).run(pending)).isEqualTo(DownloadSession.Result.COMPLETED);
        assertThat(sha256(downloads.resolve("video.bin"))).isEqualTo(pending.sha256Checksum());
    }

    @Test
    void neverOverwritesAnExistingDownload() throws Exception {
        Files.createDirectories(downloads);
        Files.writeString(downloads.resolve("notes.txt"), "keep me");
        Path source = randomFile("notes.txt", 1000);

        assertThat(receiverSession(Long.MAX_VALUE).run(share(source))).isEqualTo(DownloadSession.Result.COMPLETED);
        assertThat(Files.readString(downloads.resolve("notes.txt"))).isEqualTo("keep me");
        assertThat(Files.mismatch(source, downloads.resolve("notes (2).txt"))).isEqualTo(-1);
    }

    // --- Declined before data -------------------------------------------------------

    @Test
    void fileNotSharedFromThisDeviceIsDeclined() throws Exception {
        PendingTransfer pending = share(randomFile("secret.txt", 100));
        Files.delete(shareIndex.location()); // the sender's device no longer lists it

        assertThat(receiverSession(Long.MAX_VALUE).run(pending)).isEqualTo(DownloadSession.Result.REJECTED);
        assertThat(senderServer.reports).containsExactly("rejected:FILE_UNAVAILABLE");
        assertThat(downloadEntries()).isEmpty();
    }

    @Test
    void sourceWhoseSizeChangedIsDeclined() throws Exception {
        Path source = randomFile("log.txt", 1000);
        PendingTransfer pending = share(source);
        Files.write(source, new byte[] {1}, java.nio.file.StandardOpenOption.APPEND);

        assertThat(receiverSession(Long.MAX_VALUE).run(pending)).isEqualTo(DownloadSession.Result.REJECTED);
        assertThat(senderServer.reports).containsExactly("rejected:FILE_CHANGED");
    }

    @Test
    void fileTooLargeForTheReceiverIsDeclinedWithoutConnecting() throws Exception {
        PendingTransfer pending = share(randomFile("big.iso", 5000));

        assertThat(receiverSession(4999).run(pending)).isEqualTo(DownloadSession.Result.REJECTED);
        assertThat(receiverServer.reports).containsExactly("rejected:FILE_TOO_LARGE");
        assertThat(senderServer.reports).isEmpty();
        assertThat(authority.reportedOutcomes).isEmpty();
    }

    @Test
    void offerThatDoesNotMatchTheServersMetadataIsDeclined() throws Exception {
        PendingTransfer pending = share(randomFile("a.bin", 1000));
        PendingTransfer tampered = new PendingTransfer(pending.transferId(), pending.sharedFileId(), sender,
                pending.fileName(), pending.fileSizeBytes(), "0".repeat(64), CHUNK, pending.totalChunks());

        assertThat(receiverSession(Long.MAX_VALUE).run(tampered)).isEqualTo(DownloadSession.Result.REJECTED);
        assertThat(receiverServer.reports).containsExactly("rejected:METADATA_MISMATCH");
        assertThat(downloadEntries()).isEmpty();
    }

    // --- Integrity --------------------------------------------------------------------

    @Test
    void contentChangedSinceSharingIsDetectedAndNothingIsSaved() throws Exception {
        Path source = randomFile("report.pdf", 300_000);
        PendingTransfer pending = share(source);
        Files.write(source, randomBytes(300_000, 99)); // same size, different content

        assertThat(receiverSession(Long.MAX_VALUE).run(pending)).isEqualTo(DownloadSession.Result.FAILED);
        assertThat(senderServer.reports).containsExactly("failed:FILE_CHANGED");
        assertThat(downloadEntries()).isEmpty();
    }

    @Test
    void corruptedDataFailsChecksumVerification() throws Exception {
        restartSender(evilSender((channel, grant) -> {
            byte[] chunk = randomBytes((int) grant.fileSizeBytes(), 5); // not the shared content
            channel.sendData(0, chunk, 0, chunk.length);
            channel.send(TransferMessages.end(new Summary(grant.transferId(), grant.fileSizeBytes(), grant.sha256Checksum())));
        }));
        PendingTransfer pending = share(randomFile("a.bin", 1000));

        assertThat(receiverSession(Long.MAX_VALUE).run(pending)).isEqualTo(DownloadSession.Result.FAILED);
        assertThat(receiverServer.reports).contains("failed:CHECKSUM_MISMATCH");
        assertThat(downloadEntries()).isEmpty();
    }

    @Test
    void extraDataIsRejected() throws Exception {
        restartSender(evilSender((channel, grant) -> {
            channel.sendData(0, new byte[1000], 0, 1000);
            channel.sendData(1, new byte[10], 0, 10); // beyond the announced size
        }));

        assertThat(receiverSession(Long.MAX_VALUE).run(share(randomFile("a.bin", 1000))))
                .isEqualTo(DownloadSession.Result.FAILED);
        assertThat(receiverServer.reports).contains("failed:PROTOCOL_ERROR");
        assertThat(downloadEntries()).isEmpty();
    }

    @Test
    void outOfSequenceChunksAreRejected() throws Exception {
        restartSender(evilSender((channel, grant) -> channel.sendData(1, new byte[CHUNK], 0, CHUNK)));

        assertThat(receiverSession(Long.MAX_VALUE).run(share(randomFile("a.bin", 3 * CHUNK))))
                .isEqualTo(DownloadSession.Result.FAILED);
        assertThat(receiverServer.reports).contains("failed:PROTOCOL_ERROR");
    }

    @Test
    void truncatedTransferIsRejected() throws Exception {
        restartSender(evilSender((channel, grant) -> {
            channel.sendData(0, new byte[CHUNK], 0, CHUNK);
            channel.send(TransferMessages.end(new Summary(grant.transferId(), grant.fileSizeBytes(), grant.sha256Checksum())));
        }));

        assertThat(receiverSession(Long.MAX_VALUE).run(share(randomFile("a.bin", 2 * CHUNK))))
                .isEqualTo(DownloadSession.Result.FAILED);
        assertThat(receiverServer.reports).contains("failed:PROTOCOL_ERROR");
        assertThat(downloadEntries()).isEmpty();
    }

    // --- Authorization ----------------------------------------------------------------

    @Test
    void revokedAccessStopsTheSenderMidTransfer() throws Exception {
        restartSender(new UploadSession(shareIndex, senderServer, 2, TIMEOUT, Duration.ZERO));
        senderServer.status = () -> new TransferState("IN_PROGRESS", false);

        assertThat(receiverSession(Long.MAX_VALUE).run(share(randomFile("a.bin", 1_000_000))))
                .isEqualTo(DownloadSession.Result.FAILED);
        assertThat(senderServer.reports).containsExactly("failed:PERMISSION_DENIED");
        assertThat(receiverServer.reports).noneMatch(report -> report.startsWith("failed:"));
        assertThat(downloadEntries()).isEmpty();
    }

    @Test
    void cancellationStopsTheReceiver() throws Exception {
        receiverServer.progress = () -> new TransferState("CANCELLED", false);

        assertThat(receiverSession(Long.MAX_VALUE).run(share(randomFile("a.bin", 1_000_000))))
                .isEqualTo(DownloadSession.Result.FAILED);
        assertThat(receiverServer.reports).noneMatch(report -> report.startsWith("failed:"));
        assertThat(downloadEntries()).isEmpty();
    }

    @Test
    void aPlainConnectionTicketGrantsNoFiles() throws Exception {
        IssuedTicket ticket = authority.issueTicket(receiver, receiverTls.identity().fingerprint(), sender,
                senderAddress(), senderTls.identity().fingerprint(), null);
        try (RawPeer raw = rawReceiver()) {
            raw.magic().send(Handshake.hello(receiver, ticket.ticket()));
            assertThat(raw.receive().type()).isEqualTo(MessageType.HELLO_ACK);
            raw.send(TransferMessages.request(UUID.randomUUID()));

            Message reply = raw.receive();
            assertThat(reply.type()).isEqualTo(MessageType.TRANSFER_REJECT);
            assertThat(reply.optionalField("reason")).isEqualTo("PERMISSION_DENIED");
        }
    }

    @Test
    void aTicketForOneTransferCannotFetchAnother() throws Exception {
        PendingTransfer authorized = share(randomFile("allowed.txt", 100));
        TransferGrant grant = new TransferGrant(authorized.transferId(), authorized.sharedFileId(), authorized.fileName(),
                authorized.fileSizeBytes(), authorized.sha256Checksum(), CHUNK);
        IssuedTicket ticket = authority.issueTicket(receiver, receiverTls.identity().fingerprint(), sender,
                senderAddress(), senderTls.identity().fingerprint(), grant);
        try (RawPeer raw = rawReceiver()) {
            raw.magic().send(Handshake.hello(receiver, ticket.ticket()));
            raw.receive();
            raw.send(TransferMessages.request(UUID.randomUUID()));

            assertThat(raw.receive().optionalField("reason")).isEqualTo("PERMISSION_DENIED");
        }
    }

    // --- Concurrency, disconnects, shutdown -------------------------------------------

    @Test
    void senderServesAtMostItsConfiguredNumberOfTransfers() throws Exception {
        restartSender(new UploadSession(shareIndex, senderServer, 1, TIMEOUT, Duration.ofSeconds(5)));
        PendingTransfer first = share(randomFile("first.bin", 1000));
        try (RawPeer stalled = rawReceiver()) {
            startRawTransfer(stalled, first); // receives the offer, then never accepts: holds the only slot

            PendingTransfer second = share(randomFile("second.bin", 1000));
            assertThat(receiverSession(Long.MAX_VALUE).run(second)).isEqualTo(DownloadSession.Result.REJECTED);
            assertThat(senderServer.reports).containsExactly("rejected:PEER_BUSY");
        }
    }

    @Test
    void senderDisconnectingMidTransferLeavesNoPartialFile() throws Exception {
        restartSender(evilSender((channel, grant) -> {
            channel.sendData(0, new byte[CHUNK], 0, CHUNK);
            throw new java.io.IOException("simulated crash"); // the connection is closed abruptly
        }));

        assertThat(receiverSession(Long.MAX_VALUE).run(share(randomFile("a.bin", 4 * CHUNK))))
                .isEqualTo(DownloadSession.Result.FAILED);
        assertThat(receiverServer.reports).contains("failed:CONNECTION_LOST");
        assertThat(downloadEntries()).isEmpty();
    }

    @Test
    void receiverDisconnectingMidTransferIsReportedBySender() throws Exception {
        PendingTransfer pending = share(randomFile("a.bin", 32L * 1024 * 1024));
        try (RawPeer raw = rawReceiver()) {
            startRawTransfer(raw, pending);
            raw.send(TransferMessages.accept(pending.transferId()));
            raw.readData(1024 * 1024); // part of the file... then vanish
        }
        await(() -> senderServer.reported("failed:"));
        assertThat(senderServer.reports).containsExactly("failed:CONNECTION_LOST");
    }

    @Test
    void downloadManagerFetchesRequestedTransfers() throws Exception {
        Path source = randomFile("queued.bin", 200_000);
        receiverServer.enqueue(share(source));
        PeerConnector connector = new PeerConnector(receiver, authority.issuerFor(receiver,
                receiverTls.identity().fingerprint()), receiverTls, PeerConnector.Settings.defaults());

        try (DownloadManager manager = new DownloadManager(receiverServer, handle -> new DownloadSession(connector,
                receiverServer, downloads, Long.MAX_VALUE, TIMEOUT, Duration.ofMillis(50), handle), 2,
                Duration.ofMillis(50))) {
            manager.start();
            await(() -> Files.exists(downloads.resolve("queued.bin")));
        }
        assertThat(Files.mismatch(source, downloads.resolve("queued.bin"))).isEqualTo(-1);
    }

    @Test
    void shuttingDownDuringATransferDiscardsThePartialFile() throws Exception {
        restartSender(evilSender((channel, grant) -> {
            channel.sendData(0, new byte[CHUNK], 0, CHUNK);
            Thread.sleep(10_000); // a slow sender
        }));
        receiverServer.enqueue(share(randomFile("slow.bin", 4 * CHUNK)));
        PeerConnector connector = new PeerConnector(receiver, authority.issuerFor(receiver,
                receiverTls.identity().fingerprint()), receiverTls, PeerConnector.Settings.defaults());
        DownloadManager manager = new DownloadManager(receiverServer, handle -> new DownloadSession(connector,
                receiverServer, downloads, Long.MAX_VALUE, Duration.ofSeconds(30), Duration.ofMillis(50), handle), 2,
                Duration.ofMillis(50));
        manager.start();
        await(() -> receiverServer.reported("progress:"));

        manager.close();

        await(() -> receiverServer.reported("failed:"));
        assertThat(receiverServer.reports).contains("failed:AGENT_SHUTDOWN");
        assertThat(downloadEntries()).isEmpty();
    }

    // --- Chunk sizes (H2) -----------------------------------------------------------------

    @Test
    void transfersAtTheSmallestAndLargestSupportedChunkSizes() throws Exception {
        for (int chunkSize : new int[] {ProtocolConstants.MIN_DATA_CHUNK_BYTES, ProtocolConstants.MAX_DATA_CHUNK_BYTES}) {
            Path source = randomFile("edge-" + chunkSize + ".bin", 12L * 1024 * 1024 + 7);
            PendingTransfer pending = share(source, chunkSize);

            assertThat(receiverSession(Long.MAX_VALUE).run(pending)).as("chunk size %d", chunkSize)
                    .isEqualTo(DownloadSession.Result.COMPLETED);
            assertThat(Files.mismatch(source, downloads.resolve(source.getFileName().toString()))).isEqualTo(-1);
        }
    }

    @Test
    void formerlyStallingTinyChunkSizesAreRefusedByTheReceiverBeforeConnecting() throws Exception {
        for (int chunkSize : new int[] {8, 16, ProtocolConstants.MIN_DATA_CHUNK_BYTES - 1,
                ProtocolConstants.MAX_DATA_CHUNK_BYTES + 1}) {
            receiverServer.reports.clear();
            PendingTransfer pending = share(randomFile("tiny-" + chunkSize + ".bin", 12L * 1024 * 1024), chunkSize);

            long start = System.nanoTime();
            assertThat(receiverSession(Long.MAX_VALUE).run(pending)).isEqualTo(DownloadSession.Result.REJECTED);

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            assertThat(receiverServer.reports).containsExactly("rejected:PROTOCOL_ERROR");
        }
        assertThat(authority.reportedOutcomes).isEmpty(); // never connected
        assertThat(downloadEntries()).isEmpty();
    }

    @Test
    void senderRefusesAGrantWithAnUnsupportedChunkSize() throws Exception {
        PendingTransfer pending = share(randomFile("tiny.bin", 1000), 16);
        try (RawPeer raw = rawReceiver()) {
            TransferGrant grant = new TransferGrant(pending.transferId(), pending.sharedFileId(), pending.fileName(),
                    pending.fileSizeBytes(), pending.sha256Checksum(), 16);
            IssuedTicket ticket = authority.issueTicket(receiver, receiverTls.identity().fingerprint(), sender,
                    senderAddress(), senderTls.identity().fingerprint(), grant);
            raw.magic().send(Handshake.hello(receiver, ticket.ticket()));
            raw.receive();
            raw.send(TransferMessages.request(pending.transferId()));

            Message reply = raw.receive();
            assertThat(reply.type()).isEqualTo(MessageType.TRANSFER_REJECT);
            assertThat(reply.optionalField("reason")).isEqualTo("PROTOCOL_ERROR");
        }
        assertThat(senderServer.reports).containsExactly("rejected:PROTOCOL_ERROR");
    }

    @Test
    void acknowledgementsAreCountedInBytesSoSmallChunksCannotFloodTheSender() throws Exception {
        int chunk = ProtocolConstants.MIN_DATA_CHUNK_BYTES;
        long size = 12L * 1024 * 1024;
        java.util.concurrent.atomic.AtomicInteger acks = new java.util.concurrent.atomic.AtomicInteger();
        // A sender that pushes the whole file without reading any acknowledgement until the end.
        restartSender(evilSender((channel, grant) -> {
            try (java.io.InputStream in = Files.newInputStream(shareIndex.lookup(grant.sharedFileId()).orElseThrow())) {
                byte[] buffer = new byte[chunk];
                for (int index = 0; ; index++) {
                    int read = in.readNBytes(buffer, 0, chunk);
                    if (read == 0) {
                        break;
                    }
                    channel.sendData(index, buffer, 0, read);
                }
            }
            channel.send(TransferMessages.end(new Summary(grant.transferId(), grant.fileSizeBytes(), grant.sha256Checksum())));
            while (true) {
                Message message = channel.receive();
                if (message.type() != MessageType.TRANSFER_ACK) {
                    assertThat(message.type()).isEqualTo(MessageType.TRANSFER_COMPLETE);
                    return;
                }
                acks.incrementAndGet();
            }
        }));
        PendingTransfer pending = share(randomFile("many-chunks.bin", size), chunk);

        assertThat(receiverSession(Long.MAX_VALUE).run(pending)).isEqualTo(DownloadSession.Result.COMPLETED);
        // One per MiB: 12, where counting every 32 chunks of 4 KiB would have sent 96.
        assertThat(acks.get()).isEqualTo((int) (size / ProtocolConstants.ACK_INTERVAL_BYTES));
    }

    // --- Publication and completion reports (H3, H4) ------------------------------------

    @Test
    void savedFileSurvivesFailingToTellTheSenderAndIsNotReportedAsFailed() throws Exception {
        Path source = randomFile("kept.bin", 700_000);
        PendingTransfer pending = share(source);
        ActiveConnections connections = new ActiveConnections();
        // Right after the final authorization check, i.e. before publication, the connection dies:
        // publication still happens, and only sending TRANSFER_COMPLETE fails.
        receiverServer.progressUnreachableAt = bytes -> {
            if (bytes == pending.fileSizeBytes()) {
                connections.closeAll();
            }
            return false;
        };

        DownloadSession.Result result = receiverSession(connections, reports(null)).run(pending);

        assertThat(result).isEqualTo(DownloadSession.Result.COMPLETED);
        assertThat(Files.mismatch(source, downloads.resolve("kept.bin"))).isEqualTo(-1);
        assertThat(downloadEntries()).containsExactly("kept.bin"); // no leftover partial file either
        assertThat(receiverServer.reports).contains("completed:700000:" + pending.sha256Checksum())
                .noneMatch(report -> report.startsWith("failed:"));
        // The sender lost the connection after sending everything: it leaves the outcome to the receiver.
        Thread.sleep(300);
        assertThat(senderServer.reports).noneMatch(report -> report.startsWith("failed:"));
    }

    @Test
    void transientCompletionReportFailuresAreRetriedUntilTheServerConfirms() throws Exception {
        PendingTransfer pending = share(randomFile("retry.bin", 100_000));
        receiverServer.completionAnswers.add(TransferCoordinator.ReportResult.UNDELIVERED);
        receiverServer.completionAnswers.add(TransferCoordinator.ReportResult.UNDELIVERED);

        DownloadSession.Result result = receiverSession(new ActiveConnections(), reports(temp.resolve("journal"))).run(pending);

        assertThat(result).isEqualTo(DownloadSession.Result.COMPLETED);
        assertThat(receiverServer.count("completed:")).isEqualTo(3);
        assertThat(reports(temp.resolve("journal")).pending()).isZero();
    }

    @Test
    void repeatedReportFailuresKeepTheFileAndTheReportUntilTheServerIsBack() throws Exception {
        Path source = randomFile("later.bin", 100_000);
        PendingTransfer pending = share(source);
        CompletionReports reports = reports(temp.resolve("journal"));
        // The transfer itself works; only the completion report keeps failing past the retry deadline.
        for (int i = 0; i < 50; i++) {
            receiverServer.completionAnswers.add(TransferCoordinator.ReportResult.UNDELIVERED);
        }

        DownloadSession.Result result = receiverSession(new ActiveConnections(), reports).run(pending);

        assertThat(result).isEqualTo(DownloadSession.Result.SAVED_UNCONFIRMED);
        assertThat(Files.mismatch(source, downloads.resolve("later.bin"))).isEqualTo(-1);
        assertThat(receiverServer.reports).noneMatch(report -> report.startsWith("failed:"));
        assertThat(reports.pending()).isEqualTo(1);

        // Later (next poll, or after a restart with a fresh instance over the same journal): delivered.
        receiverServer.completionAnswers.clear();
        reports(temp.resolve("journal")).retryPending();
        assertThat(reports.pending()).isZero();
        assertThat(receiverServer.reports.getLast()).isEqualTo("completed:100000:" + pending.sha256Checksum());
    }

    @Test
    void completionRefusedBecauseTheServerRecordedAnotherOutcomeIsNotRetriedAndKeepsTheFile() throws Exception {
        Path source = randomFile("refused.bin", 100_000);
        PendingTransfer pending = share(source);
        receiverServer.completionAnswers.add(TransferCoordinator.ReportResult.REFUSED);
        CompletionReports reports = reports(temp.resolve("journal"));

        DownloadSession.Result result = receiverSession(new ActiveConnections(), reports).run(pending);

        assertThat(result).isEqualTo(DownloadSession.Result.SAVED_UNCONFIRMED);
        assertThat(receiverServer.count("completed:")).isEqualTo(1);
        assertThat(Files.mismatch(source, downloads.resolve("refused.bin"))).isEqualTo(-1);
        assertThat(reports.pending()).isZero();
    }

    // --- Final authorization check fails closed --------------------------------------------

    @Test
    void unconfirmableFinalAuthorizationDiscardsTheFileInsteadOfPublishingIt() throws Exception {
        PendingTransfer pending = share(randomFile("unconfirmed.bin", 300_000));
        receiverServer.progressUnreachableAt = bytes -> bytes == pending.fileSizeBytes();

        assertThat(receiverSession(Long.MAX_VALUE).run(pending)).isEqualTo(DownloadSession.Result.FAILED);

        assertThat(downloadEntries()).isEmpty();
        assertThat(receiverServer.count("progress-unreachable:300000")).isEqualTo(4); // first try + 3 retries
        assertThat(receiverServer.reports).contains("failed:SERVER_UNAVAILABLE").noneMatch(r -> r.startsWith("completed:"));
    }

    @Test
    void briefServerOutageDuringTheFinalCheckIsRetried() throws Exception {
        Path source = randomFile("blip.bin", 300_000);
        PendingTransfer pending = share(source);
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(2);
        receiverServer.progressUnreachableAt = bytes -> bytes == pending.fileSizeBytes() && failures.getAndDecrement() > 0;

        assertThat(receiverSession(Long.MAX_VALUE).run(pending)).isEqualTo(DownloadSession.Result.COMPLETED);
        assertThat(Files.mismatch(source, downloads.resolve("blip.bin"))).isEqualTo(-1);
    }

    @Test
    void revocationDiscoveredByTheFinalCheckDiscardsTheFile() throws Exception {
        PendingTransfer pending = share(randomFile("revoked.bin", 300_000));
        receiverServer.progress = () -> new TransferState("IN_PROGRESS", true);
        receiverServer.progressUnreachableAt = bytes -> {
            if (bytes == pending.fileSizeBytes()) {
                receiverServer.progress = () -> new TransferState("FAILED", false);
            }
            return false;
        };

        assertThat(receiverSession(Long.MAX_VALUE).run(pending)).isEqualTo(DownloadSession.Result.FAILED);
        assertThat(downloadEntries()).isEmpty();
        assertThat(receiverServer.reports).noneMatch(report -> report.startsWith("completed:"));
    }

    // --- Harness ----------------------------------------------------------------------

    @FunctionalInterface
    interface EvilBehaviour {
        void afterAccept(ProtocolChannel channel, TransferGrant grant) throws Exception;
    }

    /** A sender that offers the authorized file correctly, then misbehaves. */
    private static IncomingTransferHandler evilSender(EvilBehaviour behaviour) {
        return (connection, request) -> {
            TransferGrant grant = connection.ticket().transfer();
            connection.channel().send(TransferMessages.offer(new Offer(grant.transferId(), grant.fileName(),
                    grant.fileSizeBytes(), grant.sha256Checksum(), grant.chunkSizeBytes())));
            connection.input().extend(TIMEOUT);
            connection.channel().receive(); // TRANSFER_ACCEPT
            try {
                behaviour.afterAccept(connection.channel(), grant);
                connection.input().extend(TIMEOUT);
                connection.channel().receive();
            } catch (java.io.IOException | com.peerlink.agent.protocol.ProtocolException e) {
                throw e;
            } catch (Exception e) {
                throw new java.io.IOException(e);
            }
        };
    }

    private void startSender(IncomingTransferHandler handler) throws Exception {
        listener = new PeerListener(new PeerListener.Settings(LOOPBACK, 0, 4),
                new InboundConnectionHandler(sender, authority.verifierFor(sender), senderTls, handler, TIMEOUT, TIMEOUT),
                senderTls);
        listener.start();
        authority.register(sender, senderAddress(), senderTls.identity().fingerprint());
    }

    private void restartSender(IncomingTransferHandler handler) throws Exception {
        listener.close();
        startSender(handler);
    }

    private InetSocketAddress senderAddress() {
        return new InetSocketAddress(LOOPBACK, listener.port());
    }

    private DownloadSession receiverSession(long maxFileSize) {
        PeerConnector connector = new PeerConnector(receiver, authority.issuerFor(receiver,
                receiverTls.identity().fingerprint()), receiverTls, PeerConnector.Settings.defaults());
        return new DownloadSession(connector, receiverServer, downloads, maxFileSize, TIMEOUT, Duration.ofMillis(50));
    }

    private DownloadSession receiverSession(ActiveConnections connections, CompletionReports reports) {
        PeerConnector connector = new PeerConnector(receiver, authority.issuerFor(receiver,
                receiverTls.identity().fingerprint()), receiverTls, PeerConnector.Settings.defaults());
        return new DownloadSession(connector, receiverServer, downloads, Long.MAX_VALUE, TIMEOUT, Duration.ofMillis(50),
                connections, reports);
    }

    private CompletionReports reports(Path journal) {
        return new CompletionReports(receiverServer, journal, Duration.ofMillis(10), Duration.ofMillis(300));
    }

    private PendingTransfer share(Path file) throws Exception {
        return share(file, CHUNK);
    }

    /** Shares a file from the sender's device and authorizes a transfer of it to the receiver. */
    private PendingTransfer share(Path file, int chunk) throws Exception {
        UUID fileId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        shareIndex.put(fileId, file);
        long size = Files.size(file);
        String checksum = sha256(file);
        String name = file.getFileName().toString();
        authority.grant(new TransferGrant(transferId, fileId, name, size, checksum, chunk));
        return new PendingTransfer(transferId, fileId, sender, name, size, checksum, chunk,
                (int) ((size + chunk - 1) / chunk));
    }

    private RawPeer rawReceiver() throws Exception {
        return new RawPeer(senderAddress(), receiverTls, senderTls.identity().fingerprint());
    }

    /** As the receiver: handshake with a transfer ticket, request it and read the offer. */
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

    private Path randomFile(String name, long size) throws Exception {
        Path file = temp.resolve("shared").resolve(name);
        Files.createDirectories(file.getParent());
        Random random = new Random(size);
        byte[] buffer = new byte[CHUNK];
        try (var out = Files.newOutputStream(file)) {
            for (long written = 0; written < size; written += buffer.length) {
                random.nextBytes(buffer);
                out.write(buffer, 0, (int) Math.min(buffer.length, size - written));
            }
        }
        return file;
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String[] downloadEntries() throws Exception {
        if (!Files.exists(downloads)) {
            return new String[0];
        }
        try (Stream<Path> files = Files.list(downloads)) {
            return files.map(path -> path.getFileName().toString()).sorted().toArray(String[]::new);
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        boolean met;
        while (!(met = condition.getAsBoolean()) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(met).as("condition within 10 s").isTrue();
    }
}
