package com.peerlink.agent.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.peerlink.agent.testing.FakeCoordinator;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSharerTests {

    @TempDir
    Path dir;

    private final FakeCoordinator coordinator = new FakeCoordinator();

    @Test
    void hashesRegistersAndRemembersTheFile() throws Exception {
        byte[] content = new byte[200_000];
        new java.util.Random(1).nextBytes(content);
        Path file = Files.write(dir.resolve("photo.jpg"), content);
        ShareIndex index = ShareIndex.forPeer(dir.resolve("agent"), UUID.randomUUID());

        FileSharer.Shared shared = new FileSharer(coordinator, index, 1_000_000).share(file);

        assertThat(shared.sha256()).isEqualTo(DownloadTargetTests.sha256(content));
        assertThat(shared.sizeBytes()).isEqualTo(content.length);
        assertThat(coordinator.reports).containsExactly("registered:photo.jpg:200000:" + shared.sha256());
        assertThat(index.lookup(shared.fileId())).contains(file.toRealPath());
    }

    @Test
    void refusesDirectoriesTooLargeFilesAndSymbolicLinks() throws Exception {
        ShareIndex index = ShareIndex.forPeer(dir.resolve("agent"), UUID.randomUUID());
        FileSharer sharer = new FileSharer(coordinator, index, 10);
        Path big = Files.write(dir.resolve("big.bin"), new byte[11]);

        assertThatIllegalArgumentException().isThrownBy(() -> sharer.share(dir));
        assertThatIllegalArgumentException().isThrownBy(() -> sharer.share(big)).withMessageContaining("larger");
        assertThatIllegalArgumentException().isThrownBy(() -> sharer.share(dir.resolve("missing.txt")));
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Path link = Files.createSymbolicLink(dir.resolve("link.bin"), big);
            assertThatIllegalArgumentException().isThrownBy(() -> sharer.share(link)).withMessageContaining("symbolic link");
        }
        assertThat(coordinator.reports).isEmpty();
    }

    @Test
    void shareIndexIsPrivateToTheUserWherePermissionsAreSupported() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path file = Files.writeString(dir.resolve("a.txt"), "a");
        ShareIndex index = ShareIndex.forPeer(dir.resolve("agent-data"), UUID.randomUUID());

        new FileSharer(coordinator, index, 100).share(file);

        assertThat(Files.getPosixFilePermissions(dir.resolve("agent-data")))
                .containsExactlyInAnyOrder(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
    }
}
