package com.peerlink.agent.security;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Client side: trusts exactly one server certificate, identified by the SHA-256 fingerprint the
 * coordination server reported for the target peer, and only if the server selected the PeerLink
 * application protocol. Everything else fails the TLS handshake.
 */
final class PinnedServerTrustManager extends X509ExtendedTrustManager {

    /** Distinguishes "wrong peer" from other handshake failures. */
    static final class FingerprintMismatchException extends CertificateException {
        FingerprintMismatchException() {
            super("The peer's TLS certificate does not match the fingerprint registered for it");
        }
    }

    private final String expectedFingerprint;

    PinnedServerTrustManager(String expectedFingerprint) {
        this.expectedFingerprint = expectedFingerprint;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("No server certificate");
        }
        if (!CertificateFingerprints.matches(CertificateFingerprints.sha256(chain[0]), expectedFingerprint)) {
            throw new FingerprintMismatchException();
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        checkServerTrusted(chain, authType);
        PeerTls.requireNegotiated(socket instanceof SSLSocket ssl ? ssl.getHandshakeApplicationProtocol() : null);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        checkServerTrusted(chain, authType);
        PeerTls.requireNegotiated(engine == null ? null : engine.getHandshakeApplicationProtocol());
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("Client certificates are not checked by the connecting side");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        checkClientTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
