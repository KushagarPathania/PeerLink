package com.peerlink.agent.networking;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * Socket input with an overall deadline. A plain read timeout restarts with every byte, so a peer
 * trickling one byte at a time could hold a connection forever; here every read is limited to the
 * time remaining until the deadline.
 */
public final class DeadlineInputStream extends InputStream {

    private final Socket socket;
    private final InputStream in;
    private long deadlineNanos;

    public DeadlineInputStream(Socket socket, Duration timeout) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        extend(timeout);
    }

    /** Starts a new deadline {@code timeout} from now. */
    public void extend(Duration timeout) {
        this.deadlineNanos = System.nanoTime() + timeout.toNanos();
    }

    @Override
    public int read() throws IOException {
        applyRemainingTime();
        return in.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        applyRemainingTime();
        return in.read(buffer, offset, length);
    }

    private void applyRemainingTime() throws IOException {
        long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000;
        if (remainingMillis <= 0) {
            throw new SocketTimeoutException("Deadline exceeded");
        }
        socket.setSoTimeout((int) Math.min(remainingMillis, Integer.MAX_VALUE));
    }
}
