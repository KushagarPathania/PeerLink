package com.peerlink.agent.transfer;

import com.peerlink.agent.transfer.TransferCoordinator.ReportResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tells the coordination server that a verified file was saved, and keeps trying until the
 * server gives a definite answer. The file is never deleted or re-labelled because a report
 * failed, and success is only ever what the server confirms.
 *
 * <p>Transient failures (server unreachable, 5xx, a concurrent update) are retried with
 * exponential backoff until {@code deadline}. Before the first attempt the report is written to a
 * small journal (transfer id, size and SHA-256 only); if it is still undelivered when the
 * deadline passes, or the agent stops, {@link #retryPending()} sends it again on later polls and
 * after a restart. A journal entry is removed as soon as the server accepts the report or
 * definitively refuses it (for example because it already recorded the transfer as failed or
 * timed out), so entries cannot accumulate: every transfer eventually reaches a final state.
 */
public final class CompletionReports {

    /** What {@link #deliver} achieved. */
    public enum Outcome {
        CONFIRMED,
        /** The server refused the report; it already recorded another outcome. */
        REFUSED,
        /** Not delivered yet; kept in the journal (if any) for a later retry. */
        PENDING
    }

    private static final Logger LOG = System.getLogger(CompletionReports.class.getName());
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);
    private static final String SUFFIX = ".completion";
    private static final Pattern ENTRY = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" + Pattern.quote(SUFFIX) + "$");

    private final TransferCoordinator coordinator;
    private final Path journal;
    private final Duration initialBackoff;
    private final Duration deadline;

    /**
     * @param journalDirectory where undelivered reports are kept, or {@code null} for none
     * @param deadline         how long {@link #deliver} keeps retrying before leaving it to the journal
     */
    public CompletionReports(TransferCoordinator coordinator, Path journalDirectory, Duration initialBackoff,
                             Duration deadline) {
        this.coordinator = Objects.requireNonNull(coordinator);
        this.journal = journalDirectory == null ? null : journalDirectory.toAbsolutePath().normalize();
        this.initialBackoff = initialBackoff;
        this.deadline = deadline;
    }

    /** Reports a completed transfer, retrying transient failures until the deadline. */
    public Outcome deliver(UUID transferId, long bytes, String sha256) {
        record(transferId, bytes, sha256);
        long end = System.nanoTime() + deadline.toNanos();
        long backoff = initialBackoff.toMillis();
        for (int attempt = 1; ; attempt++) {
            ReportResult result = coordinator.reportCompleted(transferId, bytes, sha256);
            if (result != ReportResult.UNDELIVERED) {
                return settled(transferId, result);
            }
            if (System.nanoTime() + backoff * 1_000_000 > end) {
                break;
            }
            LOG.log(Level.INFO, "Transfer {0}: completion report attempt {1} not delivered; retrying in {2} ms",
                    transferId, attempt, backoff);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = Math.min(backoff * 2, MAX_BACKOFF.toMillis());
        }
        LOG.log(Level.WARNING, "Transfer {0}: the file is saved, but the server has not confirmed it yet{1}", transferId,
                journal == null ? "" : "; the report will be retried");
        return Outcome.PENDING;
    }

    /** One more attempt for every journaled report; called on each transfer poll and at startup. Never throws. */
    public void retryPending() {
        if (journal == null || !Files.isDirectory(journal)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(journal)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!ENTRY.matcher(name).matches() || !Files.isRegularFile(entry)) {
                    continue;
                }
                UUID transferId = UUID.fromString(name.substring(0, name.length() - SUFFIX.length()));
                Properties report = new Properties();
                try (InputStream in = Files.newInputStream(entry)) {
                    report.load(in);
                }
                long bytes;
                String sha256 = report.getProperty("sha256", "");
                try {
                    bytes = Long.parseLong(report.getProperty("bytes", ""));
                } catch (NumberFormatException e) {
                    LOG.log(Level.WARNING, "Discarding unreadable completion report {0}", name);
                    Files.deleteIfExists(entry);
                    continue;
                }
                ReportResult result = coordinator.reportCompleted(transferId, bytes, sha256);
                if (result != ReportResult.UNDELIVERED) {
                    settled(transferId, result);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot retry pending completion reports: {0}", e.getClass().getSimpleName());
        }
    }

    /** Reports still waiting for a definite answer; for tests and diagnostics. */
    public int pending() {
        if (journal == null || !Files.isDirectory(journal)) {
            return 0;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(journal, "*" + SUFFIX)) {
            int count = 0;
            for (var iterator = entries.iterator(); iterator.hasNext(); iterator.next()) {
                count++;
            }
            return count;
        } catch (IOException e) {
            return 0;
        }
    }

    private Outcome settled(UUID transferId, ReportResult result) {
        forget(transferId);
        if (result == ReportResult.ACCEPTED) {
            LOG.log(Level.INFO, "Transfer {0}: the server confirmed the completion", transferId);
            return Outcome.CONFIRMED;
        }
        LOG.log(Level.WARNING, "Transfer {0}: the server refused the completion report; it recorded another outcome "
                + "(the saved file is kept)", transferId);
        return Outcome.REFUSED;
    }

    private void record(UUID transferId, long bytes, String sha256) {
        if (journal == null) {
            return;
        }
        Properties report = new Properties();
        report.setProperty("bytes", Long.toString(bytes));
        report.setProperty("sha256", sha256);
        try {
            ShareIndex.createPrivateDirectory(journal.getParent());
            ShareIndex.createPrivateDirectory(journal);
            Path temp = Files.createTempFile(journal, ".report-", ".tmp");
            try {
                try (OutputStream out = Files.newOutputStream(temp)) {
                    report.store(out, "PeerLink completion report awaiting the server's confirmation");
                }
                Path target = entry(transferId);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Transfer {0}: cannot keep the completion report for later retries: {1}",
                    transferId, e.getClass().getSimpleName());
        }
    }

    private void forget(UUID transferId) {
        if (journal == null) {
            return;
        }
        try {
            Files.deleteIfExists(entry(transferId));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Transfer {0}: cannot remove the delivered completion report", transferId);
        }
    }

    private Path entry(UUID transferId) {
        return journal.resolve(transferId + SUFFIX);
    }
}
