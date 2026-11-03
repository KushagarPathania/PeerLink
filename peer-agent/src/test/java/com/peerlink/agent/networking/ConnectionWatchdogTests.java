package com.peerlink.agent.networking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peerlink.agent.security.PeerTls;
import com.peerlink.agent.security.TlsIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Write deadlines and aborts on real loopback sockets whose reader never reads. */
@Timeout(30)
class ConnectionWatchdogTests {

    private final ConnectionWatchdog watchdog = new ConnectionWatchdog("test-watchdog");
    private ServerSocket server;
    private Socket accepted;
    private Socket client;

    @AfterEach
    void close() throws IOException {
        watchdog.close();
        for (AutoCloseable closeable : new AutoCloseable[] {client, accepted, server}) {
            try {
                if (closeable != null) {
                    closeable.close();
                }
            } catch (Exception e) {
                // cleanup
            }
        }
    }

    @Test
    void aWriteBlockedLongerThanTheDeadlineFailsAndLeavesNoTaskBehind() throws Exception {
        connectWithSmallBuffers();
        ConnectionWatchdog.Guard guard = watchdog.guard(client, Duration.ofMillis(500));
        OutputStream out = guard.output(client.getOutputStream());

        long start = System.nanoTime();
        assertThatThrownBy(() -> writeForever(out)).isInstanceOf(IOException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(guard.writeTimedOut()).isTrue();
        assertThat(client.isClosed()).isTrue();
        guard.close();
        assertThat(watchdog.queuedTasks()).isZero();
    }

    @Test
    void abortingFromAnotherThreadUnblocksATlsWrite() throws Exception {
        connectWithSmallBuffers();
        PeerTls serverTls = new PeerTls(TlsIdentity.generate("server"));
        PeerTls clientTls = new PeerTls(TlsIdentity.generate("client"));
        CompletableFuture<SSLSocket> serverSide = CompletableFuture.supplyAsync(() -> {
            try {
                SSLSocket socket = serverTls.serverSide(accepted);
                socket.startHandshake();
                return socket; // then never reads
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        SSLSocket tls = clientTls.clientSide(client, serverTls.identity().fingerprint());
        tls.startHandshake();
        serverSide.get(10, TimeUnit.SECONDS);
        ConnectionWatchdog.Guard guard = watchdog.guard(client, Duration.ofMinutes(5)); // only the abort counts
        OutputStream out = guard.output(tls.getOutputStream());

        CompletableFuture<Throwable> writer = CompletableFuture.supplyAsync(() -> {
            try {
                writeForever(out);
                return null;
            } catch (IOException e) {
                return e;
            }
        });
        Thread.sleep(1500); // blocked by now: kernel buffers are a few KiB
        assertThat(writer).isNotDone();

        long start = System.nanoTime();
        guard.abort(); // closes the plain socket, not the TLS layer (whose close could wait for the writer)

        assertThat(writer.get(5, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(guard.aborted()).isTrue();
        assertThat(guard.writeTimedOut()).isFalse();
        assertThatThrownBy(() -> out.write(1)).isInstanceOf(IOException.class);
        guard.close();
        assertThat(watchdog.queuedTasks()).isZero();
    }

    @Test
    void aWriterThatKeepsProgressingIsNotCutOff() throws Exception {
        // Default buffers: once they are full, every write waits for the slow reader, but only briefly.
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
        accepted = server.accept();
        ConnectionWatchdog.Guard guard = watchdog.guard(client, Duration.ofSeconds(1));
        OutputStream out = guard.output(client.getOutputStream());
        InputStream in = accepted.getInputStream();
        CompletableFuture<Long> reader = CompletableFuture.supplyAsync(() -> {
            long total = 0;
            byte[] buffer = new byte[1024];
            try {
                for (int read; (read = in.read(buffer)) >= 0; total += read) {
                    Thread.sleep(5); // slow, but steady
                }
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return total;
        });

        byte[] chunk = new byte[4096];
        long start = System.nanoTime();
        long written = 0;
        while (System.nanoTime() - start < Duration.ofSeconds(4).toNanos()) { // four deadlines long
            out.write(chunk); // each write may block briefly, never beyond the deadline
            written += chunk.length;
        }
        client.shutdownOutput();

        assertThat(reader.get(10, TimeUnit.SECONDS)).isEqualTo(written);
        assertThat(guard.writeTimedOut()).isFalse();
        assertThat(guard.aborted()).isFalse();
    }

    @Test
    void finishedGuardsDoNotAccumulateTasks() throws Exception {
        connectWithSmallBuffers();
        for (int i = 0; i < 1000; i++) {
            watchdog.guard(client, Duration.ofSeconds(30)).close();
            watchdog.schedule(() -> { }, Duration.ofMinutes(5)).cancel(false);
        }
        assertThat(watchdog.queuedTasks()).isZero();
    }

    private void connectWithSmallBuffers() throws IOException {
        server = new ServerSocket();
        server.setReceiveBufferSize(4096);
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        client = new Socket();
        client.setSendBufferSize(4096);
        client.connect(server.getLocalSocketAddress(), 2000);
        accepted = server.accept();
        accepted.setReceiveBufferSize(4096);
    }

    private static void writeForever(OutputStream out) throws IOException {
        byte[] chunk = new byte[64 * 1024];
        while (true) {
            out.write(chunk);
        }
    }
}
