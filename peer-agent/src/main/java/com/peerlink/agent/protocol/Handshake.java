package com.peerlink.agent.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.regex.Pattern;

/** Builds and validates the handshake and connection-test messages of protocol version 1. */
public final class Handshake {

    public static final Pattern TICKET = Pattern.compile("plt_[A-Za-z0-9_-]{43}");
    public static final Pattern PROOF = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern VERSION = Pattern.compile("[1-9][0-9]{0,2}");

    /** Initiator's opening message. {@link #toString()} hides the ticket. */
    public record Hello(List<Integer> versions, UUID peerId, String ticket) {
        @Override
        public String toString() {
            return "Hello[versions=" + versions + ", peerId=" + peerId + ", ticket=****]";
        }
    }

    /** Responder's acceptance. {@link #toString()} hides the proof. */
    public record HelloAck(int version, UUID peerId, String proof) {
        @Override
        public String toString() {
            return "HelloAck[version=" + version + ", peerId=" + peerId + ", proof=****]";
        }
    }

    private Handshake() {
    }

    public static Message hello(UUID peerId, String ticket) {
        StringBuilder versions = new StringBuilder();
        for (int version : ProtocolConstants.SUPPORTED_VERSIONS) {
            versions.append(versions.isEmpty() ? "" : ",").append(version);
        }
        return Message.of(MessageType.HELLO, "versions", versions.toString(), "peerId", peerId.toString(),
                "ticket", ticket);
    }

    public static Hello parseHello(Message message) throws ProtocolException {
        expect(message, MessageType.HELLO);
        String[] offered = message.field("versions").split(",", -1);
        if (offered.length > ProtocolConstants.MAX_OFFERED_VERSIONS) {
            throw malformed("Too many protocol versions offered");
        }
        List<Integer> versions = new ArrayList<>();
        for (String version : offered) {
            if (!VERSION.matcher(version).matches()) {
                throw malformed("Invalid protocol version list");
            }
            versions.add(Integer.parseInt(version));
        }
        String ticket = message.field("ticket");
        if (!TICKET.matcher(ticket).matches()) {
            throw malformed("Invalid ticket format");
        }
        return new Hello(List.copyOf(versions), parsePeerId(message.field("peerId")), ticket);
    }

    /** Highest version both sides support, if any. */
    public static OptionalInt negotiate(List<Integer> offered) {
        return offered.stream().filter(ProtocolConstants.SUPPORTED_VERSIONS::contains).mapToInt(Integer::intValue).max();
    }

    public static Message helloAck(int version, UUID peerId, String proof) {
        return Message.of(MessageType.HELLO_ACK, "version", Integer.toString(version), "peerId", peerId.toString(),
                "proof", proof);
    }

    public static HelloAck parseHelloAck(Message message) throws ProtocolException {
        expect(message, MessageType.HELLO_ACK);
        String version = message.field("version");
        if (!VERSION.matcher(version).matches()) {
            throw malformed("Invalid protocol version");
        }
        String proof = message.field("proof");
        if (!PROOF.matcher(proof).matches()) {
            throw malformed("Invalid proof format");
        }
        return new HelloAck(Integer.parseInt(version), parsePeerId(message.field("peerId")), proof);
    }

    public static Message reject(RejectReason reason) {
        return Message.of(MessageType.REJECT, "reason", reason.name());
    }

    public static Message ping(String nonce) {
        return Message.of(MessageType.PING, "nonce", nonce);
    }

    public static Message pong(String nonce) {
        return Message.of(MessageType.PONG, "nonce", nonce);
    }

    public static Message bye(RejectReason reason) {
        return reason == null ? Message.of(MessageType.BYE) : Message.of(MessageType.BYE, "reason", reason.name());
    }

    public static String nonce(Message message) throws ProtocolException {
        String nonce = message.field("nonce");
        if (!NONCE.matcher(nonce).matches()) {
            throw malformed("Invalid nonce");
        }
        return nonce;
    }

    private static UUID parsePeerId(String value) throws ProtocolException {
        if (!UUID_TEXT.matcher(value).matches()) {
            throw malformed("Invalid peer id");
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
