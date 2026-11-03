package com.peerlink.agent.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Fixed parameters of the PeerLink wire protocol (see docs/TCP_PROTOCOL.md). */
public final class ProtocolConstants {

    /** Sent once by the initiator before the first frame; anything else is not a PeerLink client. */
    public static final byte[] MAGIC = "PLNK".getBytes(StandardCharsets.US_ASCII);
    /** Version 2: TLS 1.3 transport, control and data frames, file transfer. Version 1 is no longer spoken. */
    public static final int PROTOCOL_VERSION = 2;
    public static final List<Integer> SUPPORTED_VERSIONS = List.of(PROTOCOL_VERSION);
    /** TLS application protocol (ALPN) both sides must negotiate. */
    public static final String ALPN = "peerlink/2";

    /** Largest control frame payload accepted. Checked before any buffer is allocated. */
    public static final int MAX_FRAME_BYTES = 4096;
    /**
     * Smallest registered chunk size. Tiny chunks only add per-frame overhead; the coordination
     * server refuses to register anything outside {@code MIN..MAX}, and both agents check it again.
     */
    public static final int MIN_DATA_CHUNK_BYTES = 4 * 1024;
    /** Largest file chunk in one data frame. */
    public static final int MAX_DATA_CHUNK_BYTES = 64 * 1024;
    /**
     * The receiver acknowledges whenever this many bytes arrived since its last acknowledgement...
     * Counting bytes (not chunks) bounds the acknowledgements the sender has not read yet to about
     * {@code SEND_WINDOW_BYTES / ACK_INTERVAL_BYTES}, whatever the chunk size, so they always fit in
     * the socket buffers and neither side can block the other.
     */
    public static final long ACK_INTERVAL_BYTES = 1024L * 1024;
    /** ...and the sender never runs further ahead of the last acknowledgement than this. */
    public static final long SEND_WINDOW_BYTES = 8L * 1024 * 1024;
    public static final int MAX_FIELDS = 16;
    public static final int MAX_KEY_LENGTH = 32;
    public static final int MAX_VALUE_LENGTH = 256;
    public static final int MAX_OFFERED_VERSIONS = 8;
    /** Connection tests per connection before the responder ends it. */
    public static final int MAX_PINGS_PER_CONNECTION = 16;

    private ProtocolConstants() {
    }

    /** Whether a registered or offered chunk size is one this protocol supports. */
    public static boolean supportedChunkSize(long chunkSize) {
        return chunkSize >= MIN_DATA_CHUNK_BYTES && chunkSize <= MAX_DATA_CHUNK_BYTES;
    }
}
