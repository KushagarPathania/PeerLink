package com.peerlink.agent.transfer;

import com.peerlink.agent.protocol.TransferError;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * A shared file opened for sending. It must still be the regular file that was shared (not a
 * symbolic link, same size), and must not change while it is being read; changes are detected
 * by size, modification time and finally the SHA-256 the server holds.
 */
final class SourceFile implements Closeable {

    private final Path path;
    private final FileChannel channel;
    private final long expectedSize;
    private final BasicFileAttributes before;

    private SourceFile(Path path, FileChannel channel, long expectedSize, BasicFileAttributes before) {
        this.path = path;
        this.channel = channel;
        this.expectedSize = expectedSize;
        this.before = before;
    }

    static SourceFile open(Path path, long expectedSize) throws TransferException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new TransferException(TransferError.FILE_UNAVAILABLE, "The shared file is missing or no longer a regular file");
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (channel.size() != expectedSize) {
                channel.close();
                throw new TransferException(TransferError.FILE_CHANGED, "The shared file's size changed since it was shared");
            }
            return new SourceFile(path, channel, expectedSize, attributes);
        } catch (IOException e) {
            closeQuietly(channel);
            throw new TransferException(TransferError.FILE_UNAVAILABLE, "The shared file cannot be read");
        }
    }

    /** Reads exactly {@code length} bytes into {@code buffer}, or fewer only if the file ended early. */
    int readFully(byte[] buffer, int length) throws IOException {
        ByteBuffer target = ByteBuffer.wrap(buffer, 0, length);
        while (target.hasRemaining()) {
            if (channel.read(target) < 0) {
                break;
            }
        }
        return target.position();
    }

    /** After sending {@code expectedSize} bytes: nothing more to read, and size and timestamp unchanged. */
    void requireUnchanged() throws TransferException {
        try {
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean unchanged = channel.size() == expectedSize && after.size() == expectedSize
                    && after.lastModifiedTime().equals(before.lastModifiedTime())
                    && channel.read(ByteBuffer.allocate(1)) < 0;
            if (!unchanged) {
                throw new TransferException(TransferError.FILE_CHANGED, "The shared file changed while it was being sent");
            }
        } catch (IOException e) {
            throw new TransferException(TransferError.FILE_CHANGED, "The shared file changed while it was being sent");
        }
    }

    @Override
    public void close() {
        closeQuietly(channel);
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                // Reading only; nothing to lose.
            }
        }
    }
}
