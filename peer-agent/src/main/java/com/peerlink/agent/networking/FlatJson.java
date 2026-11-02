package com.peerlink.agent.networking;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal, strict JSON for the flat objects exchanged with the coordination server: one object of
 * string, number, boolean or null values, with no nesting. The agent deliberately has no JSON library.
 * Numbers are returned as their text. Anything else is rejected with IllegalArgumentException.
 */
public final class FlatJson {

    static final int MAX_INPUT_LENGTH = 16 * 1024;

    private final String text;
    private int position;

    private FlatJson(String text) {
        this.text = text;
    }

    public static Map<String, String> parseObject(String json) {
        Objects.requireNonNull(json, "json");
        if (json.length() > MAX_INPUT_LENGTH) {
            throw new IllegalArgumentException("JSON input too long");
        }
        return new FlatJson(json).object();
    }

    /** Serializes string values (and numbers given as {@link Number}) into a JSON object. */
    public static String writeObject(Map<String, ?> fields) {
        StringBuilder out = new StringBuilder("{");
        fields.forEach((key, value) -> {
            if (out.length() > 1) {
                out.append(',');
            }
            appendString(out, key);
            out.append(':');
            if (value instanceof Number number) {
                out.append(number);
            } else {
                appendString(out, String.valueOf(value));
            }
        });
        return out.append('}').toString();
    }

    private Map<String, String> object() {
        Map<String, String> fields = new LinkedHashMap<>();
        skipWhitespace();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            position++;
        } else {
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                if (fields.containsKey(key)) {
                    throw new IllegalArgumentException("Duplicate key");
                }
                fields.put(key, value());
                skipWhitespace();
                char next = next();
                if (next == '}') {
                    break;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("Expected , or }");
                }
            }
        }
        skipWhitespace();
        if (position != text.length()) {
            throw new IllegalArgumentException("Trailing content");
        }
        return fields;
    }

    private String value() {
        char c = peek();
        if (c == '"') {
            return string();
        }
        if (c == '{' || c == '[') {
            throw new IllegalArgumentException("Nested values are not supported");
        }
        int start = position;
        while (position < text.length() && "-+.eE0123456789truefalsn".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
        String literal = text.substring(start, position);
        if (literal.equals("null")) {
            return null;
        }
        if (literal.equals("true") || literal.equals("false")
                || literal.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][-+]?[0-9]+)?")) {
            return literal;
        }
        throw new IllegalArgumentException("Invalid value");
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw new IllegalArgumentException("Control character in string");
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escape = next();
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (position + 4 > text.length()) {
                        throw new IllegalArgumentException("Truncated escape");
                    }
                    try {
                        out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid escape", e);
                    }
                    position += 4;
                }
                default -> throw new IllegalArgumentException("Invalid escape");
            }
        }
    }

    private static void appendString(StringBuilder out, String value) {
        out.append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private void skipWhitespace() {
        while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
    }

    private void expect(char expected) {
        if (next() != expected) {
            throw new IllegalArgumentException("Expected '" + expected + "'");
        }
    }

    private char peek() {
        if (position >= text.length()) {
            throw new IllegalArgumentException("Unexpected end of input");
        }
        return text.charAt(position);
    }

    private char next() {
        char c = peek();
        position++;
        return c;
    }
}
