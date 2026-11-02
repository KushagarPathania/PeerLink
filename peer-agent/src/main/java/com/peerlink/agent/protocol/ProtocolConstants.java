package com.peerlink.agent.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Fixed parameters of the PeerLink wire protocol (see docs/TCP_PROTOCOL.md). */
public final class ProtocolConstants {

    /** Sent once by the initiator before the first frame; anything else is not a PeerLink client. */
    public static final byte[] MAGIC = "PLNK".getBytes(StandardCharsets.US_ASCII);
    public static final int PROTOCOL_VERSION = 1;
    public static final List<Integer> SUPPORTED_VERSIONS = List.of(PROTOCOL_VERSION);

    /** Largest frame payload accepted. Checked before any buffer is allocated. */
    public static final int MAX_FRAME_BYTES = 4096;
    public static final int MAX_FIELDS = 16;
    public static final int MAX_KEY_LENGTH = 32;
    public static final int MAX_VALUE_LENGTH = 256;
    public static final int MAX_OFFERED_VERSIONS = 8;
    /** Connection tests per connection before the responder ends it. */
    public static final int MAX_PINGS_PER_CONNECTION = 16;

    private ProtocolConstants() {
    }
}
