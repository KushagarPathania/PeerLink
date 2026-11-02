package com.peerlink.agent.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MessageCodecTests {

    @Test
    void roundTripsMessages() throws Exception {
        Message ping = Handshake.ping("nonce-1");

        Message decoded = MessageCodec.decode(MessageCodec.encode(ping));

        assertThat(decoded).isEqualTo(ping);
        assertThat(new String(MessageCodec.encode(ping), StandardCharsets.UTF_8)).isEqualTo("type=PING\nnonce=nonce-1\n");
    }

    @Test
    void unknownMessageTypeIsRejected() {
        assertReject("type=GIVE_ME_FILES\n", RejectReason.UNKNOWN_MESSAGE_TYPE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "type=PING\nnonce=abc",              // no final line break
            "nonce=abc\ntype=PING\n",            // type not first
            "type=PING\nnonce=abc\nnonce=def\n", // duplicate field
            "type=PING\nticket=abc\n",           // field not allowed for this type
            "type=PING\nnonce=\n",               // empty value
            "type=PING\nno-separator\n",         // not key=value
            "type=PING\n1nonce=abc\n",           // invalid key
            "type=PING\nnonce=a\tb\n",           // control character
            "type=PING\r\nnonce=abc\n",          // CRLF is not accepted
            "\n"                                 // empty line
    })
    void malformedMessagesAreRejected(String text) {
        assertReject(text, RejectReason.MALFORMED);
    }

    @Test
    void tooManyFieldsAreRejected() {
        assertReject("type=PING\n" + "nonce=a\n".repeat(ProtocolConstants.MAX_FIELDS + 1), RejectReason.MALFORMED);
    }

    @Test
    void overlongValueIsRejected() {
        assertReject("type=PING\nnonce=" + "a".repeat(ProtocolConstants.MAX_VALUE_LENGTH + 1) + "\n",
                RejectReason.MALFORMED);
    }

    @Test
    void invalidUtf8IsRejected() {
        byte[] payload = {'t', 'y', 'p', 'e', '=', (byte) 0xC3, (byte) 0x28, '\n'};

        assertThatThrownBy(() -> MessageCodec.decode(payload))
                .isInstanceOfSatisfying(ProtocolException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.MALFORMED));
    }

    @Test
    void messagesThatWouldBreakFramingCannotBeConstructed() {
        assertThatThrownBy(() -> Handshake.ping("a\nb")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Message.of(MessageType.PING, "ticket", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertReject(String text, RejectReason reason) {
        assertThatThrownBy(() -> MessageCodec.decode(text.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ProtocolException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
