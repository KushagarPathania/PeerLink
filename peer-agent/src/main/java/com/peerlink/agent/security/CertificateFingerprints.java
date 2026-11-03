package com.peerlink.agent.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** SHA-256 fingerprints of DER-encoded certificates, as lower-case hex. */
public final class CertificateFingerprints {

    public static final Pattern FORMAT = Pattern.compile("[0-9a-f]{64}");

    private CertificateFingerprints() {
    }

    public static String sha256(Certificate certificate) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (CertificateEncodingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot fingerprint certificate", e);
        }
    }

    /** Constant-time comparison of two fingerprints. */
    public static boolean matches(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
