package com.peerlink.agent.protocol;

import com.peerlink.agent.protocol.FrameIO.ControlFrame;
import com.peerlink.agent.protocol.FrameIO.DataFrame;
import com.peerlink.agent.protocol.FrameIO.Frame;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Sends and receives whole messages and file chunks over a pair of streams; knows nothing about sockets. */
public final class ProtocolChannel {

    /** Something received: a control message or a file chunk. */
    public sealed interface Incoming permits Control, Data {
    }

    public record Control(Message message) implements Incoming {
    }

    public record Data(int index, byte[] bytes) implements Incoming {
    }

    private final InputStream in;
    private final OutputStream out;

    public ProtocolChannel(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    public void send(Message message) throws IOException {
        FrameIO.writeControl(out, MessageCodec.encode(message));
    }

    public void sendData(int index, byte[] buffer, int offset, int length) throws IOException {
        FrameIO.writeData(out, index, buffer, offset, length);
    }

    /** The next control message; a data frame here is a protocol violation. */
    public Message receive() throws IOException, ProtocolException {
        Incoming incoming = receiveAny();
        if (incoming instanceof Control control) {
            return control.message();
        }
        throw new ProtocolException(RejectReason.UNEXPECTED_MESSAGE, "Unexpected data frame");
    }

    public Incoming receiveAny() throws IOException, ProtocolException {
        Frame frame = FrameIO.readFrame(in);
        return switch (frame) {
            case ControlFrame control -> new Control(MessageCodec.decode(control.payload()));
            case DataFrame data -> new Data(data.index(), data.data());
        };
    }
}
