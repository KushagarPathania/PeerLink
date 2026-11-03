package com.peerlink.agent.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.peerlink.agent.networking.CoordinationServerClient.JsonResponse;
import com.peerlink.agent.testing.FakeCoordinator;
import com.peerlink.agent.transfer.TransferCoordinator.ReportResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompletionReportsTests {

    private static final String SHA = "a".repeat(64);

    @TempDir
    Path temp;

    private final FakeCoordinator server = new FakeCoordinator();
    private final UUID transfer = UUID.randomUUID();

    @Test
    void confirmedOnFirstAttemptLeavesNothingBehind() {
        CompletionReports reports = reports(Duration.ofSeconds(1));

        assertThat(reports.deliver(transfer, 10, SHA)).isEqualTo(CompletionReports.Outcome.CONFIRMED);
        assertThat(server.count("completed:")).isEqualTo(1);
        assertThat(reports.pending()).isZero();
    }

    @Test
    void retriesStopAtTheDeadlineAndTheReportSurvivesARestart() {
        server.unreachable = true;
        long start = System.nanoTime();

        assertThat(reports(Duration.ofMillis(200)).deliver(transfer, 10, SHA)).isEqualTo(CompletionReports.Outcome.PENDING);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(server.count("completed:")).isBetween(2L, 10L); // backoff, not a busy loop
        // "Restart": a new instance over the same journal still knows about it, until the server confirms.
        CompletionReports afterRestart = reports(Duration.ofMillis(200));
        assertThat(afterRestart.pending()).isEqualTo(1);
        afterRestart.retryPending();
        assertThat(afterRestart.pending()).isEqualTo(1);
        server.unreachable = false;
        afterRestart.retryPending();
        assertThat(afterRestart.pending()).isZero();
        assertThat(server.reports.getLast()).isEqualTo("completed:10:" + SHA);
    }

    @Test
    void aDefinitiveRefusalIsNeitherRetriedNorKept() {
        server.completionAnswers.add(ReportResult.REFUSED);
        CompletionReports reports = reports(Duration.ofSeconds(5));

        assertThat(reports.deliver(transfer, 10, SHA)).isEqualTo(CompletionReports.Outcome.REFUSED);
        assertThat(server.count("completed:")).isEqualTo(1);
        assertThat(reports.pending()).isZero();
    }

    @Test
    void journaledReportRefusedLaterIsDropped() {
        server.unreachable = true;
        CompletionReports reports = reports(Duration.ZERO);
        reports.deliver(transfer, 10, SHA);
        server.unreachable = false;
        server.completionAnswers.add(ReportResult.REFUSED); // e.g. the server timed the transfer out meanwhile

        reports.retryPending();

        assertThat(reports.pending()).isZero();
    }

    @Test
    void unrelatedOrDamagedJournalFilesAreIgnoredOrDropped() throws Exception {
        Path journal = Files.createDirectories(temp.resolve("journal"));
        Files.writeString(journal.resolve("notes.txt"), "not a report");
        Files.writeString(journal.resolve(UUID.randomUUID() + ".completion"), "bytes=oops\n");

        reports(Duration.ZERO).retryPending();

        assertThat(server.reports).isEmpty();
        assertThat(journal.resolve("notes.txt")).exists();
        assertThat(reports(Duration.ZERO).pending()).isZero();
    }

    @Test
    void serverAnswersAreClassifiedAsRetryableOrFinal() {
        assertThat(classify(200, Map.of())).isEqualTo(ReportResult.ACCEPTED);
        assertThat(classify(409, Map.of("code", "CONCURRENT_MODIFICATION"))).isEqualTo(ReportResult.UNDELIVERED);
        for (int status : new int[] {408, 429, 500, 502, 503}) {
            assertThat(classify(status, Map.of())).as("HTTP %d", status).isEqualTo(ReportResult.UNDELIVERED);
        }
        assertThat(classify(409, Map.of("code", "TRANSFER_STATE_CONFLICT"))).isEqualTo(ReportResult.REFUSED);
        for (int status : new int[] {400, 401, 403, 404, 409}) {
            assertThat(classify(status, Map.of())).as("HTTP %d", status).isEqualTo(ReportResult.REFUSED);
        }
    }

    private static ReportResult classify(int status, Map<String, String> body) {
        return CoordinationTransferCoordinator.classifyCompletion(new JsonResponse(status, body));
    }

    private CompletionReports reports(Duration deadline) {
        return new CompletionReports(server, temp.resolve("journal"), Duration.ofMillis(20), deadline);
    }
}
