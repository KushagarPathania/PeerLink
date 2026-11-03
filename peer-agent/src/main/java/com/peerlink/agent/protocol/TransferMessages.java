package com.peerlink.agent.protocol;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Builds and strictly parses the file-transfer messages of protocol version 2. */
public final class TransferMessages {

    public static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern NON_NEGATIVE = Pattern.compile("0|[1-9][0-9]{0,18}");

    /** What the sender offers; every field is checked against the coordination server's metadata. */
    public record Offer(UUID transferId, String fileName, long size, String sha256, int chunkSize) {
    }

    /** Sender's or receiver's summary of the bytes that were sent or saved. */
    public record Summary(UUID transferId, long size, String sha256) {
    }

    private TransferMessages() {
    }

    public static Message request(UUID transferId) {
        return Message.of(MessageType.TRANSFER_REQUEST, "transferId", transferId.toString());
    }

    public static Message offer(Offer offer) {
        return Message.of(MessageType.TRANSFER_OFFER, "transferId", offer.transferId().toString(),
                "fileName", offer.fileName(), "size", Long.toString(offer.size()), "sha256", offer.sha256(),
                "chunkSize", Integer.toString(offer.chunkSize()));
    }

    public static Message accept(UUID transferId) {
        return Message.of(MessageType.TRANSFER_ACCEPT, "transferId", transferId.toString());
    }

    public static Message reject(UUID transferId, TransferError reason) {
        return Message.of(MessageType.TRANSFER_REJECT, "transferId", transferId.toString(), "reason", reason.name());
    }

    public static Message ack(UUID transferId, long bytes) {
        return Message.of(MessageType.TRANSFER_ACK, "transferId", transferId.toString(), "bytes", Long.toString(bytes));
    }

    public static Message end(Summary summary) {
        return summary(MessageType.TRANSFER_END, summary);
    }

    public static Message complete(Summary summary) {
        return summary(MessageType.TRANSFER_COMPLETE, summary);
    }

    public static Message failed(UUID transferId, TransferError reason) {
        return Message.of(MessageType.TRANSFER_FAILED, "transferId", transferId.toString(), "reason", reason.name());
    }

    public static Message cancel(UUID transferId, TransferError reason) {
        return Message.of(MessageType.TRANSFER_CANCEL, "transferId", transferId.toString(), "reason", reason.name());
    }

    private static Message summary(MessageType type, Summary summary) {
        return Message.of(type, "transferId", summary.transferId().toString(), "size", Long.toString(summary.size()),
                "sha256", summary.sha256());
    }

    /** The transfer id of any transfer message, which must be the one this connection is about. */
    public static UUID transferId(Message message, UUID expected) throws ProtocolException {
        UUID transferId = uuid(message.field("transferId"));
        if (!transferId.equals(Objects.requireNonNull(expected))) {
            throw new ProtocolException(RejectReason.UNEXPECTED_MESSAGE, "Message is about another transfer");
        }
        return transferId;
    }

    public static UUID requestedTransfer(Message message) throws ProtocolException {
        expect(message, MessageType.TRANSFER_REQUEST);
        return uuid(message.field("transferId"));
    }

    public static Offer parseOffer(Message message, UUID expectedTransfer) throws ProtocolException {
        expect(message, MessageType.TRANSFER_OFFER);
        long chunkSize = number(message.field("chunkSize"));
        if (!ProtocolConstants.supportedChunkSize(chunkSize)) {
            throw malformed("Unsupported chunk size");
        }
        return new Offer(transferId(message, expectedTransfer), message.field("fileName"), number(message.field("size")),
                sha256(message.field("sha256")), (int) chunkSize);
    }

    public static Summary parseSummary(Message message, MessageType type, UUID expectedTransfer)
            throws ProtocolException {
        expect(message, type);
        return new Summary(transferId(message, expectedTransfer), number(message.field("size")),
                sha256(message.field("sha256")));
    }

    public static long parseAck(Message message, UUID expectedTransfer) throws ProtocolException {
        expect(message, MessageType.TRANSFER_ACK);
        transferId(message, expectedTransfer);
        return number(message.field("bytes"));
    }

    public static TransferError reason(Message message) throws ProtocolException {
        return TransferError.parse(message.field("reason"));
    }

    private static long number(String value) throws ProtocolException {
        if (!NON_NEGATIVE.matcher(value).matches()) {
            throw malformed("Invalid number");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw malformed("Number out of range");
        }
    }

    private static String sha256(String value) throws ProtocolException {
        if (!SHA256.matcher(value).matches()) {
            throw malformed("Invalid SHA-256 checksum");
        }
        return value;
    }

    private static UUID uuid(String value) throws ProtocolException {
        if (!UUID_TEXT.matcher(value).matches()) {
            throw malformed("Invalid transfer id");
        }
        return UUID.fromString(value);
    }

    private static void expect(Message message, MessageType type) throws ProtocolException {
        if (message.type() != type) {
            throw new ProtocolException(RejectReason.UNEXPECTED_MESSAGE, "Expected " + type + " but got " + message.type());
        }
    }

    private static ProtocolException malformed(String detail) {
        return new ProtocolException(RejectReason.MALFORMED, detail);
    }
}
