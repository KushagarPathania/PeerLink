package com.peerlink.server.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Random bearer secrets and their SHA-256 fingerprints, shared by agent credentials and connection tickets. */
public final class SecretTokens {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int RANDOM_BYTES = 32;

    private SecretTokens() {
    }

    /** 256 random bits, base64url without padding (43 characters). */
    public static String randomValue() {
        byte[] random = new byte[RANDOM_BYTES];
        SECURE_RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    /** Lower-case hex SHA-256; what the database stores instead of the secret. */
    public static String sha256Hex(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
