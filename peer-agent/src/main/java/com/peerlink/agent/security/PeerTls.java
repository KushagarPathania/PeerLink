package com.peerlink.agent.security;

import com.peerlink.agent.protocol.ProtocolConstants;
import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

/**
 * Wraps peer-to-peer TCP sockets in mutually authenticated TLS 1.3 (JSSE). Both sides present
 * their ephemeral certificate; the connecting side pins the target's fingerprint during the
 * handshake, the listener verifies the connecting side's fingerprint after ticket redemption.
 * ALPN {@code peerlink/2} is required, so a non-PeerLink TLS client cannot get far, and there is
 * no plaintext mode to fall back to.
 */
public final class PeerTls {

    public static final String PROTOCOL = "TLSv1.3";

    private final TlsIdentity identity;
    private final SSLContext listenerContext;

    public PeerTls(TlsIdentity identity) {
        this.identity = identity;
        this.listenerContext = context(new DeferredClientTrustManager());
    }

    public TlsIdentity identity() {
        return identity;
    }

    /** Listener side of an accepted connection; the caller runs {@link SSLSocket#startHandshake()}. */
    public SSLSocket serverSide(Socket accepted) throws IOException {
        SSLSocket socket = (SSLSocket) listenerContext.getSocketFactory()
                .createSocket(accepted, null, accepted.getPort(), true);
        socket.setUseClientMode(false);
        configure(socket);
        socket.setNeedClientAuth(true);
        return socket;
    }

    /** Connecting side, trusting only the server certificate with {@code expectedServerFingerprint}. */
    public SSLSocket clientSide(Socket connected, String expectedServerFingerprint) throws IOException {
        SSLContext context = context(new PinnedServerTrustManager(expectedServerFingerprint));
        SSLSocket socket = (SSLSocket) context.getSocketFactory()
                .createSocket(connected, connected.getInetAddress().getHostAddress(), connected.getPort(), true);
        socket.setUseClientMode(true);
        configure(socket);
        return socket;
    }

    /** During the handshake (from the trust managers): the PeerLink application protocol must have been chosen. */
    static void requireNegotiated(String applicationProtocol) throws CertificateException {
        if (!ProtocolConstants.ALPN.equals(applicationProtocol)) {
            throw new CertificateException("The peer did not negotiate the " + ProtocolConstants.ALPN + " application protocol");
        }
    }

    /** After the handshake: both sides must have agreed on the PeerLink application protocol (checked again). */
    public static void requireApplicationProtocol(SSLSocket socket) throws SSLHandshakeException {
        if (!ProtocolConstants.ALPN.equals(socket.getApplicationProtocol())) {
            throw new SSLHandshakeException("Peer did not negotiate " + ProtocolConstants.ALPN);
        }
    }

    /** Fingerprint of the certificate the other side presented in the completed handshake. */
    public static String peerFingerprint(SSLSocket socket) throws SSLPeerUnverifiedException {
        Certificate[] chain = socket.getSession().getPeerCertificates();
        return CertificateFingerprints.sha256(chain[0]);
    }

    /** Whether a handshake failed because the server's certificate was not the pinned one. */
    public static boolean isFingerprintMismatch(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PinnedServerTrustManager.FingerprintMismatchException) {
                return true;
            }
        }
        return false;
    }

    private SSLContext context(TrustManager trustManager) {
        try {
            SSLContext context = SSLContext.getInstance(PROTOCOL);
            context.init(identity.keyManagers(), new TrustManager[] {trustManager}, new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS 1.3 is not available", e);
        }
    }

    private static void configure(SSLSocket socket) {
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setProtocols(new String[] {PROTOCOL});
        parameters.setApplicationProtocols(new String[] {ProtocolConstants.ALPN});
        parameters.setEndpointIdentificationAlgorithm(null); // identity is the pinned fingerprint, not a host name
        socket.setSSLParameters(parameters);
    }
}
