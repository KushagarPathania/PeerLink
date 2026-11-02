package com.peerlink.agent.protocol;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encodes messages as UTF-8 text, one {@code key=value} per line, each line ending in {@code \n},
 * and the first line always {@code type=<TYPE>}. Decoding is strict: anything not produced by
 * {@link #encode} is rejected with a {@link ProtocolException}.
 */
public final class MessageCodec {

    private MessageCodec() {
    }

    public static byte[] encode(Message message) {
        StringBuilder text = new StringBuilder("type=").append(message.type().name()).append('\n');
        message.fields().forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > ProtocolConstants.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Message exceeds the maximum frame size");
        }
        return bytes;
    }

    public static Message decode(byte[] payload) throws ProtocolException {
        String text = decodeUtf8(payload);
        if (text.isEmpty() || text.charAt(text.length() - 1) != '\n') {
            throw malformed("Message must end with a line break");
        }
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        if (lines.length > ProtocolConstants.MAX_FIELDS + 1) {
            throw malformed("Too many fields");
        }

        MessageType type = null;
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw malformed("Expected key=value");
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!Message.KEY.matcher(key).matches() || !Message.isValidValue(value)) {
                throw malformed("Invalid field");
            }
            if (i == 0) {
                if (!key.equals("type")) {
                    throw malformed("First field must be type");
                }
                type = MessageType.parse(value);
                continue;
            }
            if (!type.allowedFields().contains(key)) {
                throw malformed(type + " does not carry field " + key);
            }
            if (fields.put(key, value) != null) {
                throw malformed("Duplicate field " + key);
            }
        }
        return new Message(type, fields);
    }

    private static String decodeUtf8(byte[] payload) throws ProtocolException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(payload));
            return chars.toString();
        } catch (CharacterCodingException e) {
            throw malformed("Message is not valid UTF-8");
        }
    }

    private static ProtocolException malformed(String detail) {
        return new ProtocolException(RejectReason.MALFORMED, detail);
    }
}
