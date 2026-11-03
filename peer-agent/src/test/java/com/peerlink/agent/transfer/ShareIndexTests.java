package com.peerlink.agent.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShareIndexTests {

    @TempDir
    Path dataDir;

    @Test
    void remembersPathsAcrossInstancesAndProcesses() throws Exception {
        UUID peer = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        Path shared = Files.writeString(dataDir.resolve("doc.txt"), "x");

        ShareIndex.forPeer(dataDir.resolve("agent"), peer).put(fileId, shared);

        // A separate instance (as in the long-running agent) sees the new entry immediately.
        ShareIndex reader = ShareIndex.forPeer(dataDir.resolve("agent"), peer);
        assertThat(reader.lookup(fileId)).contains(shared.toAbsolutePath().normalize());
        assertThat(reader.lookup(UUID.randomUUID())).isEmpty();
        assertThat(ShareIndex.forPeer(dataDir.resolve("agent"), UUID.randomUUID()).lookup(fileId)).isEmpty();
    }

    @Test
    void existingDataDirectoryWithLoosePermissionsIsMadePrivate() throws Exception {
        Path agent = Files.createDirectories(dataDir.resolve("agent"));
        Assumptions.assumeTrue(agent.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions are not supported on this file system");
        Files.setPosixFilePermissions(agent, PosixFilePermissions.fromString("rwxr-xr-x"));

        ShareIndex.forPeer(agent, UUID.randomUUID()).put(UUID.randomUUID(), Files.writeString(dataDir.resolve("a.txt"), "x"));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(agent))).isEqualTo("rwx------");
    }

    @Test
    void emptyIndexHasNoEntries() throws Exception {
        assertThat(new ShareIndex(dataDir.resolve("missing.properties")).lookup(UUID.randomUUID())).isEmpty();
    }
}
