package com.peerlink.agent.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HandshakeTests {

    private static final UUID PEER = UUID.fromString("3f2b8c1e-6d4a-4b7e-9c2d-1a5e8f7b6c3d");
    private static final String TICKET = "plt_" + "A".repeat(43);
    private static final String PROOF = "p".repeat(43);

    @Test
    void helloRoundTrips() throws Exception {
        Handshake.Hello hello = Handshake.parseHello(Handshake.hello(PEER, TICKET));

        assertThat(hello.versions()).containsExactly(1);
        assertThat(hello.peerId()).isEqualTo(PEER);
        assertThat(hello.ticket()).isEqualTo(TICKET);
        assertThat(hello.toString()).doesNotContain(TICKET);
    }

    @Test
    void negotiatesHighestCommonVersionOrNothing() {
        assertThat(Handshake.negotiate(List.of(1, 2, 3))).hasValue(1);
        assertThat(Handshake.negotiate(List.of(2, 3))).isEmpty();
    }

    @Test
    void rejectsMalformedIdentityAndAuthorizationFields() {
        assertMalformed(Message.of(MessageType.HELLO, "versions", "1", "peerId", "not-a-uuid", "ticket", TICKET));
        assertMalformed(Message.of(MessageType.HELLO, "versions", "1", "peerId", PEER.toString().toUpperCase(),
                "ticket", TICKET));
        assertMalformed(Message.of(MessageType.HELLO, "versions", "1", "peerId", PEER.toString(), "ticket", "plk_secret"));
        assertMalformed(Message.of(MessageType.HELLO, "versions", "one", "peerId", PEER.toString(), "ticket", TICKET));
        assertMalformed(Message.of(MessageType.HELLO, "versions", "1,2,3,4,5,6,7,8,9", "peerId", PEER.toString(),
                "ticket", TICKET));
        assertMalformed(Message.of(MessageType.HELLO, "versions", "1", "peerId", PEER.toString()));
    }

    @Test
    void wrongMessageInPlaceOfHelloIsUnexpected() {
        assertThatThrownBy(() -> Handshake.parseHello(Handshake.ping("x")))
                .isInstanceOfSatisfying(ProtocolException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.UNEXPECTED_MESSAGE));
    }

    @Test
    void helloAckRoundTripsAndHidesProof() throws Exception {
        Handshake.HelloAck ack = Handshake.parseHelloAck(Handshake.helloAck(1, PEER, PROOF));

        assertThat(ack.version()).isEqualTo(1);
        assertThat(ack.peerId()).isEqualTo(PEER);
        assertThat(ack.proof()).isEqualTo(PROOF);
        assertThat(ack.toString()).doesNotContain(PROOF);
        assertMalformedAck(Message.of(MessageType.HELLO_ACK, "version", "1", "peerId", PEER.toString(), "proof", "short"));
    }

    @Test
    void messageToStringNeverShowsValues() {
        assertThat(Handshake.hello(PEER, TICKET).toString()).doesNotContain(TICKET);
    }

    private static void assertMalformed(Message message) {
        assertThatThrownBy(() -> Handshake.parseHello(message))
                .isInstanceOfSatisfying(ProtocolException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.MALFORMED));
    }

    private static void assertMalformedAck(Message message) {
        assertThatThrownBy(() -> Handshake.parseHelloAck(message))
                .isInstanceOfSatisfying(ProtocolException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.MALFORMED));
    }
}
