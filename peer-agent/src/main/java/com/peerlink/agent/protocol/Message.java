package com.peerlink.agent.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One protocol message: a type and a few string fields. Construction enforces the protocol's
 * limits, so every Message can be encoded into a valid frame. {@link #toString()} never prints
 * field values, which may be secrets.
 */
public record Message(MessageType type, Map<String, String> fields) {

    static final Pattern KEY = Pattern.compile("[a-zA-Z][a-zA-Z0-9]{0," + (ProtocolConstants.MAX_KEY_LENGTH - 1) + "}");

    public Message {
        Objects.requireNonNull(type, "type");
        Map<String, String> copy = new LinkedHashMap<>(fields);
        if (copy.size() > ProtocolConstants.MAX_FIELDS) {
            throw new IllegalArgumentException("Too many fields");
        }
        copy.forEach((key, value) -> {
            if (!type.allowedFields().contains(key)) {
                throw new IllegalArgumentException(type + " does not carry field " + key);
            }
            if (!isValidValue(value)) {
                throw new IllegalArgumentException("Invalid value for field " + key);
            }
        });
        fields = Collections.unmodifiableMap(copy);
    }

    public static Message of(MessageType type, String... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected key/value pairs");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            fields.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return new Message(type, fields);
    }

    /** A required field; its absence is a protocol violation by the sender. */
    public String field(String name) throws ProtocolException {
        String value = fields.get(name);
        if (value == null) {
            throw new ProtocolException(RejectReason.MALFORMED, type + " is missing field " + name);
        }
        return value;
    }

    public String optionalField(String name) {
        return fields.get(name);
    }

    /** Non-empty, at most MAX_VALUE_LENGTH, no control characters (and so no line breaks). */
    static boolean isValidValue(String value) {
        if (value == null || value.isEmpty() || value.length() > ProtocolConstants.MAX_VALUE_LENGTH) {
            return false;
        }
        return value.chars().noneMatch(c -> c < 0x20 || c == 0x7f);
    }

    @Override
    public String toString() {
        return "Message[" + type + ", fields=" + fields.keySet() + "]";
    }
}
