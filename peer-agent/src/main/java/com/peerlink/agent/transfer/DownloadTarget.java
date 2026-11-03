package com.peerlink.agent.transfer;

import com.peerlink.agent.protocol.TransferError;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

/**
 * Where a received file is written. Data goes to a fresh, uniquely named temporary file inside
 * the download directory (created exclusively, so never through a symbolic link). The file is
 * published under its real name only after its size and SHA-256 were verified, by an atomic
 * rename onto a name reserved with exclusive creation, so an existing file is never overwritten.
 * On any failure the temporary file is deleted.
 */
public final class DownloadTarget implements AutoCloseable {

    static final int MAX_NAME_ATTEMPTS = 1000;

    private final Path directory;
    private final String fileName;
    private final long expectedSize;
    private final Path temp;
    private final FileChannel channel;
    private final MessageDigest digest = Checksums.newSha256();
    private long written;
    private boolean finished;

    private DownloadTarget(Path directory, String fileName, long expectedSize, Path temp, FileChannel channel) {
        this.directory = directory;
        this.fileName = fileName;
        this.expectedSize = expectedSize;
        this.temp = temp;
        this.channel = channel;
    }

    /**
     * @throws TransferException INSUFFICIENT_STORAGE, METADATA_MISMATCH (unsafe name) or STORAGE_ERROR
     */
    public static DownloadTarget create(Path downloadDirectory, String fileName, long expectedSize)
            throws TransferException {
        try {
            FileNames.requireSafe(fileName);
        } catch (IllegalArgumentException e) {
            throw new TransferException(TransferError.METADATA_MISMATCH, "Unsafe file name: " + e.getMessage());
        }
        Path directory = prepareDirectory(downloadDirectory);
        requireSpace(directory, expectedSize);
        Path temp = null;
        try {
            temp = Files.createTempFile(directory, ".peerlink-", ".part");
            FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            return new DownloadTarget(directory, fileName, expectedSize, temp, channel);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new TransferException(TransferError.STORAGE_ERROR, "Cannot create a file in the download directory");
        }
    }

    /** The real download directory (symbolic links in the configured path resolved once, up front). */
    static Path prepareDirectory(Path downloadDirectory) throws TransferException {
        try {
            Files.createDirectories(downloadDirectory);
            Path real = downloadDirectory.toRealPath();
            if (!Files.isDirectory(real) || !Files.isWritable(real)) {
                throw new TransferException(TransferError.STORAGE_ERROR, "The download directory is not writable");
            }
            return real;
        } catch (IOException e) {
            throw new TransferException(TransferError.STORAGE_ERROR, "The download directory cannot be used");
        }
    }

    static void requireSpace(Path directory, long bytes) throws TransferException {
        try {
            if (Files.getFileStore(directory).getUsableSpace() < bytes) {
                throw new TransferException(TransferError.INSUFFICIENT_STORAGE, "Not enough free space for " + bytes + " bytes");
            }
        } catch (IOException e) {
            // No reliable answer on this file system: the write itself will fail if the disk fills up.
        }
    }

    /** Appends one chunk; refuses to grow beyond the expected size. */
    public void write(byte[] data) throws TransferException {
        if (written + data.length > expectedSize) {
            throw new TransferException(TransferError.PROTOCOL_ERROR, "More data than the announced file size");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException e) {
            throw new TransferException(TransferError.STORAGE_ERROR, "Writing the file failed (disk full or not writable?)");
        }
        digest.update(data);
        written += data.length;
    }

    public long written() {
        return written;
    }

    /** Flushes to disk and checks size and checksum; the file is still not visible under its name. */
    public void finish(String expectedSha256) throws TransferException {
        if (written != expectedSize) {
            throw new TransferException(TransferError.PROTOCOL_ERROR,
                    "Received " + written + " bytes but " + expectedSize + " were announced");
        }
        try {
            channel.force(true);
            channel.close();
        } catch (IOException e) {
            throw new TransferException(TransferError.STORAGE_ERROR, "Saving the file failed (disk full?)");
        }
        String actual = Checksums.hex(digest);
        if (!actual.equals(expectedSha256)) {
            throw new TransferException(TransferError.CHECKSUM_MISMATCH, "SHA-256 of the received data does not match");
        }
        finished = true;
    }

    /** Makes the verified file visible as {@code fileName}, or a numbered variant if that name is taken. */
    public Path publish() throws TransferException {
        if (!finished) {
            throw new IllegalStateException("Not verified");
        }
        for (int attempt = 1; attempt <= MAX_NAME_ATTEMPTS; attempt++) {
            String candidate = attempt == 1 ? fileName : FileNames.numbered(fileName, attempt);
            Path destination = directory.resolve(candidate).normalize();
            if (!directory.equals(destination.getParent())) {
                throw new TransferException(TransferError.METADATA_MISMATCH, "File name escapes the download directory");
            }
            try {
                // Exclusive creation reserves the name and fails on any existing entry, symlinks included.
                Files.createFile(destination);
            } catch (FileAlreadyExistsException taken) {
                continue;
            } catch (IOException e) {
                throw new TransferException(TransferError.STORAGE_ERROR, "Cannot create the destination file");
            }
            try {
                try {
                    Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                return destination;
            } catch (IOException e) {
                deleteQuietly(destination);
                throw new TransferException(TransferError.STORAGE_ERROR, "Cannot move the file into place");
            }
        }
        throw new TransferException(TransferError.STORAGE_ERROR, "Too many files with this name already exist");
    }

    /** Deletes the temporary file (idempotent); used on every failure path. */
    public void discard() {
        try {
            channel.close();
        } catch (IOException e) {
            // Discarding anyway.
        }
        deleteQuietly(temp);
    }

    @Override
    public void close() {
        if (Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) {
            discard();
        }
    }

    Path temporaryFile() {
        return temp;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Left behind; it is a hidden .part file in the download directory.
        }
    }
}
