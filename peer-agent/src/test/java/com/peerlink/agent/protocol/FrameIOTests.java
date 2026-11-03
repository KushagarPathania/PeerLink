package com.peerlink.agent.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FrameIOTests {

    @Test
    void roundTripsAControlFrame() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameIO.writeControl(out, "type=PING\nnonce=abc\n".getBytes(StandardCharsets.UTF_8));

        FrameIO.Frame frame = FrameIO.readFrame(new ByteArrayInputStream(out.toByteArray()));

        assertThat(frame).isInstanceOfSatisfying(FrameIO.ControlFrame.class, control ->
                assertThat(new String(control.payload(), StandardCharsets.UTF_8)).isEqualTo("type=PING\nnonce=abc\n"));
    }

    @Test
    void roundTripsADataFrame() throws Exception {
        byte[] chunk = new byte[ProtocolConstants.MAX_DATA_CHUNK_BYTES];
        new java.util.Random(7).nextBytes(chunk);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameIO.writeData(out, 41, chunk, 0, chunk.length);

        FrameIO.Frame frame = FrameIO.readFrame(new ByteArrayInputStream(out.toByteArray()));

        assertThat(frame).isInstanceOfSatisfying(FrameIO.DataFrame.class, data -> {
            assertThat(data.index()).isEqualTo(41);
            assertThat(data.data()).isEqualTo(chunk);
        });
    }

    @Test
    void rejectsOversizedLengthBeforeReadingOrAllocating() {
        // Announces 1 GiB but the stream ends right after the header: a naive reader would try to allocate.
        InputStream in = new CountingInputStream(header(FrameIO.CONTROL, 1 << 30));

        assertThatThrownBy(() -> FrameIO.readFrame(in))
                .isInstanceOfSatisfying(ProtocolException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.FRAME_TOO_LARGE));
        assertThat(((CountingInputStream) in).consumed()).isEqualTo(5);
    }

    @Test
    void controlAndDataFramesHaveSeparateLimits() {
        // A 64 KiB payload is fine as data but far too large for a control frame.
        int chunkFrame = 4 + ProtocolConstants.MAX_DATA_CHUNK_BYTES;
        assertThatThrownBy(() -> FrameIO.readFrame(new ByteArrayInputStream(header(FrameIO.CONTROL, chunkFrame))))
                .isInstanceOfSatisfying(ProtocolException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.FRAME_TOO_LARGE));
        assertThatThrownBy(() -> FrameIO.readFrame(new ByteArrayInputStream(header(FrameIO.DATA, chunkFrame + 1))))
                .isInstanceOfSatisfying(ProtocolException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.FRAME_TOO_LARGE));
    }

    @Test
    void rejectsLengthWithHighBitSetAsTooLarge() {
        assertThatThrownBy(() -> FrameIO.readFrame(new ByteArrayInputStream(header(FrameIO.DATA, -1))))
                .isInstanceOfSatisfying(ProtocolException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.FRAME_TOO_LARGE));
    }

    @Test
    void rejectsEmptyFramesAndDataWithoutContent() {
        assertThatThrownBy(() -> FrameIO.readFrame(new ByteArrayInputStream(header(FrameIO.CONTROL, 0))))
                .isInstanceOfSatisfying(ProtocolException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.MALFORMED));
        // A data frame needs the 4-byte index plus at least one byte.
        assertThatThrownBy(() -> FrameIO.readFrame(new ByteArrayInputStream(header(FrameIO.DATA, 4))))
                .isInstanceOfSatisfying(ProtocolException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.MALFORMED));
    }

    @Test
    void rejectsUnknownFrameKind() {
        assertThatThrownBy(() -> FrameIO.readFrame(new ByteArrayInputStream(header((byte) 'X', 10))))
                .isInstanceOfSatisfying(ProtocolException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.MALFORMED));
    }

    @Test
    void incompleteHeaderOrPayloadIsEndOfStream() {
        assertThatThrownBy(() -> FrameIO.readFrame(new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(EOFException.class);
        assertThatThrownBy(() -> FrameIO.readFrame(new ByteArrayInputStream(new byte[] {FrameIO.CONTROL, 0, 0})))
                .isInstanceOf(EOFException.class);
        byte[] truncated = ByteBuffer.allocate(7).put(FrameIO.CONTROL).putInt(10).put((byte) 'a').put((byte) 'b').array();
        assertThatThrownBy(() -> FrameIO.readFrame(new ByteArrayInputStream(truncated)))
                .isInstanceOf(EOFException.class);
    }

    @Test
    void magicMustMatch() throws Exception {
        FrameIO.readMagic(new ByteArrayInputStream("PLNK".getBytes(StandardCharsets.US_ASCII)));

        assertThatThrownBy(() -> FrameIO.readMagic(new ByteArrayInputStream("GET ".getBytes(StandardCharsets.US_ASCII))))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> FrameIO.readMagic(new ByteArrayInputStream("PL".getBytes(StandardCharsets.US_ASCII))))
                .isInstanceOf(EOFException.class);
    }

    @Test
    void refusesToWriteInvalidFrames() {
        assertThatThrownBy(() -> FrameIO.writeControl(new ByteArrayOutputStream(), new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FrameIO.writeControl(new ByteArrayOutputStream(),
                new byte[ProtocolConstants.MAX_FRAME_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] tooBig = new byte[ProtocolConstants.MAX_DATA_CHUNK_BYTES + 1];
        assertThatThrownBy(() -> FrameIO.writeData(new ByteArrayOutputStream(), 0, tooBig, 0, tooBig.length))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] header(byte kind, int length) {
        return ByteBuffer.allocate(5).put(kind).putInt(length).array();
    }

    /** Exposes how many bytes were consumed ({@code pos} counts every byte handed out). */
    private static final class CountingInputStream extends ByteArrayInputStream {
        CountingInputStream(byte[] bytes) {
            super(bytes);
        }

        int consumed() {
            return pos;
        }
    }
}
