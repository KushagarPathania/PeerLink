package com.peerlink.agent.security;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Listener side: requires the connecting peer to present a certificate and prove possession of
 * its key (that is what the TLS handshake does), but cannot yet know whose certificate to expect:
 * the connecting peer is only identified once its ticket is redeemed, inside the encrypted
 * channel. The listener then compares this certificate's fingerprint with the one the
 * coordination server bound to the ticket, and rejects the connection on any mismatch before
 * sending anything but a REJECT. This is therefore not a "trust all" manager: no connection is
 * accepted on the strength of this check alone.
 *
 * <p>It also fails the handshake unless the PeerLink application protocol was negotiated: JSSE
 * itself refuses a client offering only other protocols, but would accept one offering none.
 */
final class DeferredClientTrustManager extends X509ExtendedTrustManager {

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("Peer connections are checked with their TLS socket");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        requireCertificate(chain);
        PeerTls.requireNegotiated(socket instanceof SSLSocket ssl ? ssl.getHandshakeApplicationProtocol() : null);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        requireCertificate(chain);
        PeerTls.requireNegotiated(engine == null ? null : engine.getHandshakeApplicationProtocol());
    }

    private static void requireCertificate(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0 || chain[0] == null) {
            throw new CertificateException("A client certificate is required");
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("The listener never acts as a TLS client");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        checkServerTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        checkServerTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
