package com.peerlink.agent.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peerlink.agent.protocol.TransferMessages.Offer;
import com.peerlink.agent.protocol.TransferMessages.Summary;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferMessagesTests {

    private static final UUID TRANSFER = UUID.fromString("3f2b8c1e-6d4a-4b7e-9c2d-1a5e8f7b6c3d");
    private static final String SHA = "a".repeat(64);

    @Test
    void offerAndSummaryRoundTripThroughTheCodec() throws Exception {
        Offer offer = new Offer(TRANSFER, "Résumé final.pdf", 123_456_789L, SHA, 65536);

        Offer parsed = TransferMessages.parseOffer(MessageCodec.decode(MessageCodec.encode(TransferMessages.offer(offer))),
                TRANSFER);
        Summary end = TransferMessages.parseSummary(MessageCodec.decode(MessageCodec.encode(
                TransferMessages.end(new Summary(TRANSFER, 0, SHA)))), MessageType.TRANSFER_END, TRANSFER);

        assertThat(parsed).isEqualTo(offer);
        assertThat(end.size()).isZero();
    }

    @Test
    void rejectsInvalidSizesChecksumsAndChunkSizes() {
        assertMalformed(offer("size", "-1"));
        assertMalformed(offer("size", "99999999999999999999")); // overflows a long
        assertMalformed(offer("size", "1e9"));
        assertMalformed(offer("sha256", "A".repeat(64)));
        assertMalformed(offer("sha256", "abc"));
        assertMalformed(offer("chunkSize", "0"));
        assertMalformed(offer("chunkSize", String.valueOf(ProtocolConstants.MAX_DATA_CHUNK_BYTES + 1)));
    }

    @Test
    void acceptsExactlyTheSupportedChunkSizes() throws Exception {
        assertThat(TransferMessages.parseOffer(offer("chunkSize", "4096"), TRANSFER).chunkSize()).isEqualTo(4096);
        assertThat(TransferMessages.parseOffer(offer("chunkSize", "65536"), TRANSFER).chunkSize()).isEqualTo(65536);
        assertMalformed(offer("chunkSize", "4095"));
        assertMalformed(offer("chunkSize", "65537"));
        // The sizes that used to stall transfers are no longer accepted anywhere.
        assertMalformed(offer("chunkSize", "8"));
        assertMalformed(offer("chunkSize", "16"));
    }

    @Test
    void messagesAboutAnotherTransferAreRejected() {
        Message other = TransferMessages.accept(UUID.randomUUID());

        assertThatThrownBy(() -> TransferMessages.transferId(other, TRANSFER))
                .isInstanceOfSatisfying(ProtocolException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.UNEXPECTED_MESSAGE));
    }

    @Test
    void unknownFailureReasonsCountAsProtocolErrors() throws Exception {
        Message message = Message.of(MessageType.TRANSFER_FAILED, "transferId", TRANSFER.toString(), "reason", "WHATEVER");

        assertThat(TransferMessages.reason(message)).isEqualTo(TransferError.PROTOCOL_ERROR);
        assertThat(TransferError.CANCELLED.reportable()).isFalse();
    }

    private static Message offer(String field, String value) {
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("transferId", TRANSFER.toString());
        fields.put("fileName", "a.txt");
        fields.put("size", "10");
        fields.put("sha256", SHA);
        fields.put("chunkSize", "4096");
        fields.put(field, value);
        return new Message(MessageType.TRANSFER_OFFER, fields);
    }

    private static void assertMalformed(Message message) {
        assertThatThrownBy(() -> TransferMessages.parseOffer(message, TRANSFER))
                .isInstanceOfSatisfying(ProtocolException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.MALFORMED));
    }
}
