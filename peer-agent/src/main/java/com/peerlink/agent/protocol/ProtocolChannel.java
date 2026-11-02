package com.peerlink.agent.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Sends and receives whole messages over a pair of streams; knows nothing about sockets. */
public final class ProtocolChannel {

    private final InputStream in;
    private final OutputStream out;

    public ProtocolChannel(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    public void send(Message message) throws IOException {
        FrameIO.writeFrame(out, MessageCodec.encode(message));
    }

    public Message receive() throws IOException, ProtocolException {
        return MessageCodec.decode(FrameIO.readFrame(in));
    }
}
