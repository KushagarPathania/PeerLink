package com.peerlink.agent.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Framing: a 4-byte big-endian payload length followed by the payload. The length is validated
 * (1..{@link ProtocolConstants#MAX_FRAME_BYTES}) before any payload buffer is allocated.
 */
public final class FrameIO {

    private static final int LENGTH_BYTES = 4;

    private FrameIO() {
    }

    public static void writeMagic(OutputStream out) throws IOException {
        out.write(ProtocolConstants.MAGIC);
        out.flush();
    }

    /**
     * @throws EOFException      if the connection ends before four bytes arrive
     * @throws ProtocolException if the bytes are not the PeerLink magic
     */
    public static void readMagic(InputStream in) throws IOException, ProtocolException {
        byte[] magic = in.readNBytes(ProtocolConstants.MAGIC.length);
        if (magic.length < ProtocolConstants.MAGIC.length) {
            throw new EOFException("Connection closed before the protocol preamble");
        }
        if (!Arrays.equals(magic, ProtocolConstants.MAGIC)) {
            throw new ProtocolException(RejectReason.MALFORMED, "Not a PeerLink connection");
        }
    }

    public static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        if (payload.length == 0 || payload.length > ProtocolConstants.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Invalid frame length " + payload.length);
        }
        out.write(ByteBuffer.allocate(LENGTH_BYTES).putInt(payload.length).array());
        out.write(payload);
        out.flush();
    }

    /**
     * @throws EOFException      if the connection ends before a complete frame arrives
     * @throws ProtocolException if the announced length is zero or too large
     */
    public static byte[] readFrame(InputStream in) throws IOException, ProtocolException {
        byte[] header = in.readNBytes(LENGTH_BYTES);
        if (header.length < LENGTH_BYTES) {
            throw new EOFException(header.length == 0 ? "Connection closed" : "Incomplete frame header");
        }
        long length = Integer.toUnsignedLong(ByteBuffer.wrap(header).getInt());
        if (length == 0) {
            throw new ProtocolException(RejectReason.MALFORMED, "Empty frame");
        }
        if (length > ProtocolConstants.MAX_FRAME_BYTES) {
            throw new ProtocolException(RejectReason.FRAME_TOO_LARGE, "Frame of " + length + " bytes exceeds the limit");
        }
        byte[] payload = in.readNBytes((int) length);
        if (payload.length < length) {
            throw new EOFException("Incomplete frame");
        }
        return payload;
    }
}
