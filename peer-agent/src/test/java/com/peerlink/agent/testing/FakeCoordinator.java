package com.peerlink.agent.testing;

import com.peerlink.agent.protocol.TransferError;
import com.peerlink.agent.transfer.CoordinationException;
import com.peerlink.agent.transfer.PendingTransfer;
import com.peerlink.agent.transfer.TransferCoordinator;
import com.peerlink.agent.transfer.TransferCoordinator.ReportResult;
import com.peerlink.agent.transfer.TransferState;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

/**
 * Scriptable stand-in for the coordination server's transfer endpoints. Records every report as
 * a short string ("progress:123", "completed:...", "failed:CODE", "rejected:CODE") so tests can
 * check who reported what; it never receives file content because the interface carries none.
 */
public final class FakeCoordinator implements TransferCoordinator {

    public final List<String> reports = new CopyOnWriteArrayList<>();
    public volatile Supplier<TransferState> status = () -> new TransferState("IN_PROGRESS", true);
    public volatile Supplier<TransferState> progress = () -> new TransferState("IN_PROGRESS", true);
    public volatile boolean unreachable;
    /** Progress reports for which this returns true fail as if the server were unreachable. */
    public volatile LongPredicate progressUnreachableAt = bytes -> false;
    /** Scripted answers to completion reports, used in order; afterwards ACCEPTED (UNDELIVERED if unreachable). */
    public final Deque<ReportResult> completionAnswers = new ConcurrentLinkedDeque<>();
    private final Deque<PendingTransfer> pending = new ArrayDeque<>();

    public synchronized void enqueue(PendingTransfer transfer) {
        pending.add(transfer);
    }

    @Override
    public UUID registerSharedFile(String fileName, long sizeBytes, String sha256, String contentType, int chunkSizeBytes)
            throws CoordinationException {
        failIfUnreachable();
        reports.add("registered:" + fileName + ":" + sizeBytes + ":" + sha256);
        return UUID.nameUUIDFromBytes((fileName + sha256).getBytes());
    }

    @Override
    public synchronized Optional<PendingTransfer> nextTransfer() throws CoordinationException {
        failIfUnreachable();
        return Optional.ofNullable(pending.poll());
    }

    @Override
    public TransferState status(UUID transferId) throws CoordinationException {
        failIfUnreachable();
        return status.get();
    }

    @Override
    public TransferState reportProgress(UUID transferId, long bytesTransferred, int chunksCompleted)
            throws CoordinationException {
        failIfUnreachable();
        if (progressUnreachableAt.test(bytesTransferred)) {
            reports.add("progress-unreachable:" + bytesTransferred);
            throw new CoordinationException(0, "unreachable");
        }
        reports.add("progress:" + bytesTransferred);
        return progress.get();
    }

    @Override
    public ReportResult reportCompleted(UUID transferId, long bytesTransferred, String sha256) {
        reports.add("completed:" + bytesTransferred + ":" + sha256);
        ReportResult scripted = completionAnswers.poll();
        if (scripted != null) {
            return scripted;
        }
        return unreachable ? ReportResult.UNDELIVERED : ReportResult.ACCEPTED;
    }

    public long count(String prefix) {
        return reports.stream().filter(report -> report.startsWith(prefix)).count();
    }

    @Override
    public boolean reportFailed(UUID transferId, TransferError error) {
        if (error.reportable()) {
            reports.add("failed:" + error);
        }
        return !unreachable;
    }

    @Override
    public boolean reportRejected(UUID transferId, TransferError error) {
        if (error.reportable()) {
            reports.add("rejected:" + error);
        }
        return !unreachable;
    }

    public boolean reported(String prefix) {
        return reports.stream().anyMatch(report -> report.startsWith(prefix));
    }

    private void failIfUnreachable() throws CoordinationException {
        if (unreachable) {
            throw new CoordinationException(0, "unreachable");
        }
    }
}
