package com.peerlink.agent.testing;

import com.peerlink.agent.protocol.FrameIO;
import com.peerlink.agent.protocol.FrameIO.ControlFrame;
import com.peerlink.agent.protocol.FrameIO.DataFrame;
import com.peerlink.agent.protocol.FrameIO.Frame;
import com.peerlink.agent.protocol.Message;
import com.peerlink.agent.protocol.MessageCodec;
import com.peerlink.agent.protocol.ProtocolConstants;
import com.peerlink.agent.security.PeerTls;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocket;

/**
 * A hand-driven TLS client for sending well-formed or deliberately broken PeerLink traffic. It
 * completes a real mutually authenticated TLS handshake (pinning the listener's certificate) and
 * then lets the test write whatever it likes.
 */
public final class RawPeer implements AutoCloseable {

    private final SSLSocket socket;
    private final InputStream in;
    private final OutputStream out;

    public RawPeer(InetSocketAddress address, PeerTls tls, String listenerCertificate) throws IOException {
        this(address, tls, listenerCertificate, 0);
    }

    /**
     * @param receiveBufferBytes a small TCP receive buffer (0: system default), so that a peer that
     *                           stops reading quickly makes the other side's writes block
     */
    public RawPeer(InetSocketAddress address, PeerTls tls, String listenerCertificate, int receiveBufferBytes)
            throws IOException {
        Socket raw = new Socket();
        if (receiveBufferBytes > 0) {
            raw.setReceiveBufferSize(receiveBufferBytes);
        }
        raw.connect(address, 2000);
        raw.setSoTimeout(5000);
        socket = tls.clientSide(raw, listenerCertificate);
        socket.startHandshake();
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    public RawPeer magic() throws IOException {
        out.write(ProtocolConstants.MAGIC);
        out.flush();
        return this;
    }

    public RawPeer raw(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
        return this;
    }

    public RawPeer frame(String text) throws IOException {
        FrameIO.writeControl(out, text.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public RawPeer send(Message message) throws IOException {
        FrameIO.writeControl(out, MessageCodec.encode(message));
        return this;
    }

    public RawPeer data(int index, byte[] bytes) throws IOException {
        FrameIO.writeData(out, index, bytes, 0, bytes.length);
        return this;
    }

    public Message receive() throws Exception {
        return receiveControlSkippingData();
    }

    /** Reads the next control message, counting and discarding data frames before it. */
    public Message receiveControlSkippingData() throws Exception {
        while (true) {
            Frame frame = FrameIO.readFrame(in);
            if (frame instanceof ControlFrame control) {
                return MessageCodec.decode(control.payload());
            }
            dataFramesSkipped++;
            dataBytesSkipped += ((DataFrame) frame).data().length;
        }
    }

    /**
     * Reads data frames until at least {@code bytes} were received in total; returns {@code null}
     * then, or the control message that arrived first.
     */
    public Message readData(long bytes) throws Exception {
        while (dataBytesSkipped < bytes) {
            Frame frame = FrameIO.readFrame(in);
            if (frame instanceof ControlFrame control) {
                return MessageCodec.decode(control.payload());
            }
            dataFramesSkipped++;
            dataBytesSkipped += ((DataFrame) frame).data().length;
        }
        return null;
    }

    public int dataFramesSkipped;
    public long dataBytesSkipped;

    /** Waits for the other side to close; returns true if it did within the socket timeout. */
    public boolean closedByRemote() {
        try {
            byte[] buffer = new byte[256];
            while (in.read(buffer) >= 0) {
                // drain whatever was sent before closing
            }
            return true;
        } catch (IOException e) {
            String message = String.valueOf(e.getMessage()).toLowerCase();
            return message.contains("reset") || message.contains("closed") || message.contains("broken");
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
