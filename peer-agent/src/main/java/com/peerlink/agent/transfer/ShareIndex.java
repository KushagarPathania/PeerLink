package com.peerlink.agent.transfer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * This device's private list of shared files: server file id -> absolute local path. It stays on
 * the device; the server and other peers only ever see file ids and names. A peer can therefore
 * only obtain files the user explicitly shared, addressed by server-authorized id, never by path.
 */
public final class ShareIndex {

    private static final Logger LOG = System.getLogger(ShareIndex.class.getName());
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rwx------");

    private final Path file;

    public ShareIndex(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public static ShareIndex forPeer(Path dataDirectory, UUID peerId) {
        return new ShareIndex(dataDirectory.resolve("shared-files-" + peerId + ".properties"));
    }

    public Path location() {
        return file;
    }

    /** Read fresh on every call, so files shared by a separate {@code --share} run are seen immediately. */
    public synchronized Optional<Path> lookup(UUID fileId) throws IOException {
        String path = load().getProperty(fileId.toString());
        return path == null ? Optional.empty() : Optional.of(Path.of(path));
    }

    public synchronized void put(UUID fileId, Path path) throws IOException {
        Properties entries = load();
        entries.setProperty(fileId.toString(), path.toAbsolutePath().normalize().toString());
        Path directory = file.getParent();
        createPrivateDirectory(directory);
        Path temp = Files.createTempFile(directory, ".shared-files-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                entries.store(out, "PeerLink shared files of this device (file id = local path). Do not share.");
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Properties load() throws IOException {
        Properties entries = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                entries.load(in);
            }
        }
        return entries;
    }

    /**
     * Creates the agent's data directory if needed and makes it private to the user (rwx------),
     * also when it already existed with looser permissions. On file systems without POSIX
     * permissions (e.g. Windows) the user profile's permissions apply.
     */
    static void createPrivateDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        try {
            Set<PosixFilePermission> current = Files.getPosixFilePermissions(directory);
            if (!current.equals(PRIVATE)) {
                Files.setPosixFilePermissions(directory, PRIVATE);
                LOG.log(Level.INFO, "Restricted {0} to its owner (was {1})", directory,
                        PosixFilePermissions.toString(current));
            }
        } catch (UnsupportedOperationException e) {
            // Not a POSIX file system: rely on the user profile's permissions.
        }
    }
}
