package com.peerlink.agent.transfer;

import com.peerlink.agent.protocol.ProtocolConstants;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * {@code --share=<file>}: hashes a local file (streaming, constant memory), registers its metadata
 * with the coordination server and remembers its path in this device's {@link ShareIndex}.
 * Nothing leaves the device but the name, size and SHA-256.
 */
public final class FileSharer {

    /** What was shared. */
    public record Shared(UUID fileId, String fileName, long sizeBytes, String sha256) {
    }

    private final TransferCoordinator coordinator;
    private final ShareIndex index;
    private final long maxFileSizeBytes;

    public FileSharer(TransferCoordinator coordinator, ShareIndex index, long maxFileSizeBytes) {
        this.coordinator = coordinator;
        this.index = index;
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    /**
     * @throws IllegalArgumentException if the path is not a shareable regular file
     * @throws IOException              if it cannot be read, or changed while being hashed
     * @throws CoordinationException    if the server refused or could not be reached
     */
    public Shared share(Path path) throws IOException, CoordinationException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) {
            throw new IllegalArgumentException("Refusing to share a symbolic link; share the file it points to instead");
        }
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(absolute)) {
            throw new IllegalArgumentException("Not a readable regular file: " + absolute);
        }
        Path real = absolute.toRealPath();
        String name = FileNames.requireSafe(real.getFileName().toString());
        long size = Files.size(real);
        if (size > maxFileSizeBytes) {
            throw new IllegalArgumentException("The file is larger than the configured maximum (" + maxFileSizeBytes + " bytes)");
        }

        MessageDigest digest = Checksums.newSha256();
        byte[] buffer = new byte[ProtocolConstants.MAX_DATA_CHUNK_BYTES];
        long hashed = 0;
        try (InputStream in = Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
                hashed += read;
            }
        }
        if (hashed != size || Files.size(real) != size) {
            throw new IOException("The file changed while it was being read");
        }
        String sha256 = Checksums.hex(digest);
        String contentType = probeContentType(real);
        UUID fileId = coordinator.registerSharedFile(name, size, sha256, contentType, ProtocolConstants.MAX_DATA_CHUNK_BYTES);
        index.put(fileId, real);
        return new Shared(fileId, name, size, sha256);
    }

    private static String probeContentType(Path path) {
        try {
            String type = Files.probeContentType(path);
            return type != null && type.length() <= 255 && type.chars().noneMatch(c -> c < 0x20) ? type : null;
        } catch (IOException e) {
            return null;
        }
    }
}
