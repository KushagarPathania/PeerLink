package com.peerlink.agent.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Framing (protocol v2): a 1-byte kind, a 4-byte big-endian payload length, then the payload.
 * <ul>
 *   <li>{@code 'C'} control frame: a UTF-8 message, 1..{@link ProtocolConstants#MAX_FRAME_BYTES} bytes;</li>
 *   <li>{@code 'D'} data frame: a 4-byte chunk index followed by 1..{@link ProtocolConstants#MAX_DATA_CHUNK_BYTES}
 *       bytes of file content.</li>
 * </ul>
 * The kind and length are validated before any payload buffer is allocated.
 */
public final class FrameIO {

    public static final byte CONTROL = 'C';
    public static final byte DATA = 'D';

    private static final int LENGTH_BYTES = 4;
    private static final int INDEX_BYTES = 4;

    /** A received frame. Data frames carry exactly the received chunk. */
    public sealed interface Frame permits ControlFrame, DataFrame {
    }

    public record ControlFrame(byte[] payload) implements Frame {
    }

    public record DataFrame(int index, byte[] data) implements Frame {
    }

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

    public static void writeControl(OutputStream out, byte[] payload) throws IOException {
        if (payload.length == 0 || payload.length > ProtocolConstants.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Invalid control frame length " + payload.length);
        }
        out.write(CONTROL);
        out.write(ByteBuffer.allocate(LENGTH_BYTES).putInt(payload.length).array());
        out.write(payload);
        out.flush();
    }

    public static void writeData(OutputStream out, int index, byte[] buffer, int offset, int length) throws IOException {
        if (length <= 0 || length > ProtocolConstants.MAX_DATA_CHUNK_BYTES || index < 0) {
            throw new IllegalArgumentException("Invalid data frame");
        }
        out.write(DATA);
        out.write(ByteBuffer.allocate(LENGTH_BYTES + INDEX_BYTES).putInt(INDEX_BYTES + length).putInt(index).array());
        out.write(buffer, offset, length);
        out.flush();
    }

    /**
     * @throws EOFException      if the connection ends before a complete frame arrives
     * @throws ProtocolException if the kind is unknown or the announced length is invalid
     */
    public static Frame readFrame(InputStream in) throws IOException, ProtocolException {
        int kind = in.read();
        if (kind < 0) {
            throw new EOFException("Connection closed");
        }
        byte[] header = in.readNBytes(LENGTH_BYTES);
        if (header.length < LENGTH_BYTES) {
            throw new EOFException("Incomplete frame header");
        }
        long length = Integer.toUnsignedLong(ByteBuffer.wrap(header).getInt());
        return switch (kind) {
            case CONTROL -> new ControlFrame(readPayload(in, length, 1, ProtocolConstants.MAX_FRAME_BYTES));
            case DATA -> {
                byte[] payload = readPayload(in, length, INDEX_BYTES + 1,
                        INDEX_BYTES + ProtocolConstants.MAX_DATA_CHUNK_BYTES);
                ByteBuffer buffer = ByteBuffer.wrap(payload);
                int index = buffer.getInt();
                byte[] data = new byte[payload.length - INDEX_BYTES];
                buffer.get(data);
                yield new DataFrame(index, data);
            }
            default -> throw new ProtocolException(RejectReason.MALFORMED, "Unknown frame kind");
        };
    }

    private static byte[] readPayload(InputStream in, long length, int min, int max)
            throws IOException, ProtocolException {
        if (length > max) {
            throw new ProtocolException(RejectReason.FRAME_TOO_LARGE, "Frame of " + length + " bytes exceeds the limit");
        }
        if (length < min) {
            throw new ProtocolException(RejectReason.MALFORMED, "Frame too short");
        }
        byte[] payload = in.readNBytes((int) length);
        if (payload.length < length) {
            throw new EOFException("Incomplete frame");
        }
        return payload;
    }
}
