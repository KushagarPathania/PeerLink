package com.peerlink.agent.transfer;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Remote file names are untrusted display names. A name is only ever used as a single path
 * element inside the download directory; anything that could address another location is refused.
 */
public final class FileNames {

    public static final int MAX_BYTES = 255;

    private FileNames() {
    }

    /** @throws IllegalArgumentException if {@code name} is not a plain, portable file name */
    public static String requireSafe(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("File name is empty");
        }
        if (name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("File name is a directory reference");
        }
        for (char c : name.toCharArray()) {
            if (c == '/' || c == '\\' || c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("File name contains a path separator or control character");
            }
        }
        if (name.endsWith(".") || name.endsWith(" ")) {
            throw new IllegalArgumentException("File name must not end with a dot or space");
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("File name is longer than " + MAX_BYTES + " bytes");
        }
        try {
            Path path = Path.of(name);
            if (path.isAbsolute() || path.getNameCount() != 1 || !path.getFileName().toString().equals(name)) {
                throw new IllegalArgumentException("File name is not a single path element");
            }
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("File name is not valid on this system", e);
        }
        return name;
    }

    /** "report.pdf" -> "report (2).pdf"; "notes" -> "notes (2)"; ".env" -> ".env (2)". */
    static String numbered(String name, int number) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name + " (" + number + ")";
        }
        return name.substring(0, dot) + " (" + number + ")" + name.substring(dot);
    }
}
