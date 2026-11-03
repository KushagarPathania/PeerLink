package com.peerlink.agent.security;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;

/**
 * This agent process's TLS identity: an ephemeral EC P-256 key pair and a self-signed
 * certificate. It is generated in memory at start-up and never written to disk; peers learn its
 * SHA-256 fingerprint through the coordination server (heartbeats and ticket requests), which is
 * what authenticates it.
 */
public final class TlsIdentity {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final KeyPair keyPair;
    private final X509Certificate certificate;
    private final String fingerprint;

    private TlsIdentity(KeyPair keyPair, X509Certificate certificate) {
        this.keyPair = keyPair;
        this.certificate = certificate;
        this.fingerprint = CertificateFingerprints.sha256(certificate);
    }

    public static TlsIdentity generate(String label) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            KeyPair keyPair = generator.generateKeyPair();
            Instant now = Instant.now();
            // Validity is irrelevant for trust (the fingerprint is pinned); keep it generous.
            X509Certificate certificate = SelfSignedCertificate.create(keyPair, "PeerLink agent " + label,
                    now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(3650)), RANDOM);
            return new TlsIdentity(keyPair, certificate);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot create a TLS identity", e);
        }
    }

    /** Lower-case hex SHA-256 of the certificate; safe to share and log. */
    public String fingerprint() {
        return fingerprint;
    }

    public X509Certificate certificate() {
        return certificate;
    }

    KeyManager[] keyManagers() {
        try {
            char[] password = new char[32];
            for (int i = 0; i < password.length; i++) {
                password[i] = (char) ('a' + RANDOM.nextInt(26)); // in-memory keystore only
            }
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            keyStore.setKeyEntry("peerlink", keyPair.getPrivate(), password, new Certificate[] {certificate});
            KeyManagerFactory factory = KeyManagerFactory.getInstance("PKIX");
            factory.init(keyStore, password);
            return factory.getKeyManagers();
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new IllegalStateException("Cannot load the TLS identity", e);
        }
    }
}
