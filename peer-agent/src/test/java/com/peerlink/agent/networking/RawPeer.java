package com.peerlink.agent.networking;

import com.peerlink.agent.protocol.FrameIO;
import com.peerlink.agent.protocol.Message;
import com.peerlink.agent.protocol.MessageCodec;
import com.peerlink.agent.protocol.ProtocolConstants;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** A hand-driven client for sending well-formed or deliberately broken traffic to a listener. */
final class RawPeer implements AutoCloseable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    RawPeer(InetSocketAddress address) throws IOException {
        socket = new Socket();
        socket.connect(address, 2000);
        socket.setSoTimeout(5000);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    RawPeer magic() throws IOException {
        out.write(ProtocolConstants.MAGIC);
        out.flush();
        return this;
    }

    RawPeer raw(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
        return this;
    }

    RawPeer frame(String text) throws IOException {
        FrameIO.writeFrame(out, text.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    RawPeer send(Message message) throws IOException {
        FrameIO.writeFrame(out, MessageCodec.encode(message));
        return this;
    }

    Message receive() throws Exception {
        return MessageCodec.decode(FrameIO.readFrame(in));
    }

    /** Waits for the other side to close; returns true if it did within the socket timeout. */
    boolean closedByRemote() {
        try {
            byte[] buffer = new byte[256];
            while (in.read(buffer) >= 0) {
                // drain whatever was sent before closing
            }
            return true;
        } catch (IOException e) {
            return e.getMessage() != null && e.getMessage().toLowerCase().contains("reset");
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
