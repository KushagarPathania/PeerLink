package com.peerlink.agent.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Builds a minimal self-signed X.509 v3 certificate (no extensions) for an EC P-256 key, signed
 * with SHA256withECDSA through {@link Signature}. The JDK has no public API for creating
 * certificates, so the standard DER structure (RFC 5280) is written here; the result is parsed
 * back with {@link CertificateFactory}, which rejects anything malformed. No cryptography is
 * implemented here: identity comes from pinning the certificate's SHA-256 fingerprint, not from
 * a certificate authority.
 */
final class SelfSignedCertificate {

    private static final int[] ECDSA_WITH_SHA256 = {1, 2, 840, 10045, 4, 3, 2};
    private static final int[] COMMON_NAME = {2, 5, 4, 3};
    private static final DateTimeFormatter UTC_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter GENERALIZED_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final Instant UTC_TIME_LIMIT = Instant.parse("2050-01-01T00:00:00Z");

    private SelfSignedCertificate() {
    }

    static X509Certificate create(KeyPair keyPair, String commonName, Instant notBefore, Instant notAfter,
                                  SecureRandom random) throws GeneralSecurityException {
        byte[] algorithm = sequence(oid(ECDSA_WITH_SHA256)); // parameters must be absent for ECDSA
        byte[] name = sequence(set(sequence(oid(COMMON_NAME), tlv(0x0C, commonName.getBytes(StandardCharsets.UTF_8)))));
        byte[] serial = new byte[16];
        random.nextBytes(serial);
        byte[] tbsCertificate = sequence(
                tlv(0xA0, integer(BigInteger.TWO)),          // [0] EXPLICIT version: v3
                integer(new BigInteger(1, serial)),          // positive serial number
                algorithm,
                name,                                        // issuer = subject (self-signed)
                sequence(time(notBefore), time(notAfter)),
                name,
                keyPair.getPublic().getEncoded());           // SubjectPublicKeyInfo, already DER

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate(), random);
        signer.update(tbsCertificate);
        byte[] signature = signer.sign();
        byte[] bitString = new byte[signature.length + 1]; // leading 0: no unused bits
        System.arraycopy(signature, 0, bitString, 1, signature.length);

        byte[] der = sequence(tbsCertificate, algorithm, tlv(0x03, bitString));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] time(Instant instant) {
        return instant.isBefore(UTC_TIME_LIMIT)
                ? tlv(0x17, UTC_TIME.format(instant).getBytes(StandardCharsets.US_ASCII))
                : tlv(0x18, GENERALIZED_TIME.format(instant).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] integer(BigInteger value) {
        return tlv(0x02, value.toByteArray()); // minimal two's complement, as DER requires
    }

    private static byte[] oid(int[] arcs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(40 * arcs[0] + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int arc = arcs[i];
            int shift = 28;
            while (shift > 0 && (arc >> shift) == 0) {
                shift -= 7;
            }
            for (; shift > 0; shift -= 7) {
                out.write(0x80 | ((arc >> shift) & 0x7F));
            }
            out.write(arc & 0x7F);
        }
        return tlv(0x06, out.toByteArray());
    }

    private static byte[] sequence(byte[]... parts) {
        return tlv(0x30, parts);
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, parts);
    }

    private static byte[] tlv(int tag, byte[]... parts) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            content.writeBytes(part);
        }
        int length = content.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else {
            out.write(0x82);
            out.write(length >> 8);
            out.write(length & 0xFF);
        }
        out.writeBytes(content.toByteArray());
        return out.toByteArray();
    }
}
