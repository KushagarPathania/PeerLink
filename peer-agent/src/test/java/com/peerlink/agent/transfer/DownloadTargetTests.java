package com.peerlink.agent.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.peerlink.agent.protocol.TransferError;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadTargetTests {

    private static final byte[] CONTENT = "hello peerlink".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path downloads;

    @Test
    void writesToAHiddenTempFileAndPublishesOnlyAfterVerification() throws Exception {
        DownloadTarget target = DownloadTarget.create(downloads, "greeting.txt", CONTENT.length);
        target.write(CONTENT);

        assertThat(target.temporaryFile().getParent()).isEqualTo(downloads.toRealPath());
        assertThat(target.temporaryFile().getFileName().toString()).startsWith(".peerlink-").endsWith(".part");
        assertThat(downloads.resolve("greeting.txt")).doesNotExist();

        target.finish(sha256(CONTENT));
        Path saved = target.publish();

        assertThat(saved).isEqualTo(downloads.toRealPath().resolve("greeting.txt"));
        assertThat(Files.readAllBytes(saved)).isEqualTo(CONTENT);
        assertThat(entries()).containsExactly("greeting.txt");
    }

    @Test
    void emptyFileWorks() throws Exception {
        DownloadTarget target = DownloadTarget.create(downloads, "empty.bin", 0);
        target.finish(sha256(new byte[0]));

        assertThat(Files.size(target.publish())).isZero();
    }

    @Test
    void neverOverwritesAnExistingFile() throws Exception {
        Files.writeString(downloads.resolve("report.pdf"), "original");
        Files.writeString(downloads.resolve("report (2).pdf"), "also taken");

        DownloadTarget target = DownloadTarget.create(downloads, "report.pdf", CONTENT.length);
        target.write(CONTENT);
        target.finish(sha256(CONTENT));
        Path saved = target.publish();

        assertThat(saved.getFileName().toString()).isEqualTo("report (3).pdf");
        assertThat(Files.readString(downloads.resolve("report.pdf"))).isEqualTo("original");
    }

    @Test
    void neverWritesThroughASymbolicLinkWithTheSameName() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path outside = Files.createTempFile("outside", ".txt");
        try {
            Files.createSymbolicLink(downloads.resolve("evil.txt"), outside);

            DownloadTarget target = DownloadTarget.create(downloads, "evil.txt", CONTENT.length);
            target.write(CONTENT);
            target.finish(sha256(CONTENT));
            Path saved = target.publish();

            assertThat(saved.getFileName().toString()).isEqualTo("evil (2).txt");
            assertThat(Files.size(outside)).isZero();
            assertThat(Files.isSymbolicLink(downloads.resolve("evil.txt"))).isTrue();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsUnsafeNamesBeforeCreatingAnything() throws Exception {
        for (String name : new String[] {"../escape.txt", "/etc/passwd", "a/b", ".."}) {
            assertThatThrownBy(() -> DownloadTarget.create(downloads, name, 1))
                    .isInstanceOfSatisfying(TransferException.class,
                            e -> assertThat(e.error()).isEqualTo(TransferError.METADATA_MISMATCH));
        }
        assertThat(entries()).isEmpty();
        assertThat(downloads.getParent().resolve("escape.txt")).doesNotExist();
    }

    @Test
    void refusesExtraBytesAndWrongSizeAndCorruptContent() throws Exception {
        DownloadTarget tooMuch = DownloadTarget.create(downloads, "a.txt", 4);
        assertThatThrownBy(() -> tooMuch.write(CONTENT)).isInstanceOf(TransferException.class);
        tooMuch.discard();

        DownloadTarget truncated = DownloadTarget.create(downloads, "b.txt", CONTENT.length);
        truncated.write(new byte[] {1, 2});
        assertThatThrownBy(() -> truncated.finish(sha256(CONTENT))).isInstanceOfSatisfying(TransferException.class,
                e -> assertThat(e.error()).isEqualTo(TransferError.PROTOCOL_ERROR));
        truncated.discard();

        DownloadTarget corrupt = DownloadTarget.create(downloads, "c.txt", CONTENT.length);
        corrupt.write("HELLO PEERLINK".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> corrupt.finish(sha256(CONTENT))).isInstanceOfSatisfying(TransferException.class,
                e -> assertThat(e.error()).isEqualTo(TransferError.CHECKSUM_MISMATCH));
        corrupt.discard();

        assertThat(entries()).isEmpty(); // no temp files left behind
    }

    @Test
    void discardRemovesTheTemporaryFile() throws Exception {
        DownloadTarget target = DownloadTarget.create(downloads, "partial.bin", 100);
        target.write(new byte[10]);

        target.discard();
        target.discard(); // idempotent

        assertThat(entries()).isEmpty();
    }

    @Test
    void reportsInsufficientStorage() {
        assertThatThrownBy(() -> DownloadTarget.create(downloads, "huge.bin", Long.MAX_VALUE))
                .isInstanceOfSatisfying(TransferException.class,
                        e -> assertThat(e.error()).isEqualTo(TransferError.INSUFFICIENT_STORAGE));
    }

    @Test
    void reportsAnUnwritableDownloadDirectory() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path readOnly = Files.createDirectory(downloads.resolve("read-only"));
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-x------"));
        try {
            assertThatThrownBy(() -> DownloadTarget.create(readOnly, "x.txt", 1))
                    .isInstanceOfSatisfying(TransferException.class,
                            e -> assertThat(e.error()).isEqualTo(TransferError.STORAGE_ERROR));
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void createsTheDownloadDirectoryWhenMissing() throws Exception {
        Path nested = downloads.resolve("new/sub");

        DownloadTarget target = DownloadTarget.create(nested, "x.txt", 0);
        target.discard();

        assertThat(nested).isDirectory();
    }

    private String[] entries() throws Exception {
        try (Stream<Path> files = Files.list(downloads)) {
            return files.map(path -> path.getFileName().toString()).sorted().toArray(String[]::new);
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
