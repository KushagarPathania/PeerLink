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
    void roundTripsAFrame() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameIO.writeFrame(out, "type=PING\nnonce=abc\n".getBytes(StandardCharsets.UTF_8));

        byte[] payload = FrameIO.readFrame(new ByteArrayInputStream(out.toByteArray()));

        assertThat(new String(payload, StandardCharsets.UTF_8)).isEqualTo("type=PING\nnonce=abc\n");
    }

    @Test
    void rejectsOversizedLengthBeforeReadingOrAllocating() {
        // Announces 1 GiB but the stream ends right after the header: a naive reader would try to allocate.
        InputStream in = new CountingInputStream(header(1 << 30));

        assertThatThrownBy(() -> FrameIO.readFrame(in))
                .isInstanceOfSatisfying(ProtocolException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.FRAME_TOO_LARGE));
        assertThat(((CountingInputStream) in).consumed()).isEqualTo(4);
    }

    @Test
    void rejectsLengthWithHighBitSetAsTooLarge() {
        assertThatThrownBy(() -> FrameIO.readFrame(new ByteArrayInputStream(header(-1))))
                .isInstanceOfSatisfying(ProtocolException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.FRAME_TOO_LARGE));
    }

    @Test
    void rejectsEmptyFrame() {
        assertThatThrownBy(() -> FrameIO.readFrame(new ByteArrayInputStream(header(0))))
                .isInstanceOfSatisfying(ProtocolException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.MALFORMED));
    }

    @Test
    void incompleteHeaderOrPayloadIsEndOfStream() {
        assertThatThrownBy(() -> FrameIO.readFrame(new ByteArrayInputStream(new byte[] {0, 0})))
                .isInstanceOf(EOFException.class);
        byte[] truncated = ByteBuffer.allocate(6).putInt(10).put((byte) 'a').put((byte) 'b').array();
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
        assertThatThrownBy(() -> FrameIO.writeFrame(new ByteArrayOutputStream(), new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FrameIO.writeFrame(new ByteArrayOutputStream(),
                new byte[ProtocolConstants.MAX_FRAME_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] header(int length) {
        return ByteBuffer.allocate(4).putInt(length).array();
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
