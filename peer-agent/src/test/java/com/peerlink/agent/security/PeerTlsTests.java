package com.peerlink.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peerlink.agent.protocol.ProtocolConstants;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.Test;

/** Real TLS 1.3 handshakes over loopback between two agent identities. */
class PeerTlsTests {

    private final PeerTls listener = new PeerTls(TlsIdentity.generate("listener"));
    private final PeerTls initiator = new PeerTls(TlsIdentity.generate("initiator"));

    @Test
    void generatedCertificateIsAValidSelfSignedX509Certificate() throws Exception {
        X509Certificate certificate = listener.identity().certificate();

        certificate.checkValidity();
        certificate.verify(certificate.getPublicKey());
        assertThat(certificate.getVersion()).isEqualTo(3);
        assertThat(certificate.getSubjectX500Principal().getName()).isEqualTo("CN=PeerLink agent listener");
        assertThat(certificate.getPublicKey().getAlgorithm()).isEqualTo("EC");
        assertThat(listener.identity().fingerprint()).matches("[0-9a-f]{64}")
                .isNotEqualTo(initiator.identity().fingerprint());
    }

    @Test
    void mutualHandshakeSucceedsWithPinnedFingerprintAndExchangesData() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> clientSeenByListener = CompletableFuture.supplyAsync(() -> {
                try (SSLSocket accepted = listener.serverSide(server.accept())) {
                    accepted.startHandshake();
                    PeerTls.requireApplicationProtocol(accepted);
                    accepted.getOutputStream().write(42);
                    accepted.getOutputStream().flush();
                    accepted.getInputStream().read();
                    return PeerTls.peerFingerprint(accepted);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });

            try (SSLSocket client = initiator.clientSide(connect(server), listener.identity().fingerprint())) {
                client.startHandshake();
                PeerTls.requireApplicationProtocol(client);
                assertThat(client.getSession().getProtocol()).isEqualTo("TLSv1.3");
                assertThat(PeerTls.peerFingerprint(client)).isEqualTo(listener.identity().fingerprint());
                assertThat(client.getInputStream().read()).isEqualTo(42);
                client.getOutputStream().write(1);
                client.getOutputStream().flush();
            }
            assertThat(clientSeenByListener.get(5, TimeUnit.SECONDS)).isEqualTo(initiator.identity().fingerprint());
        }
    }

    @Test
    void connectingSideRejectsAnImpostorCertificate() throws Exception {
        PeerTls impostor = new PeerTls(TlsIdentity.generate("impostor"));
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture.runAsync(() -> {
                try (SSLSocket accepted = impostor.serverSide(server.accept())) {
                    accepted.startHandshake();
                } catch (IOException expected) {
                    // the client aborts the handshake
                }
            });

            try (SSLSocket client = initiator.clientSide(connect(server), listener.identity().fingerprint())) {
                assertThatThrownBy(client::startHandshake)
                        .isInstanceOf(SSLHandshakeException.class)
                        .satisfies(e -> assertThat(PeerTls.isFingerprintMismatch(e)).isTrue());
            }
        }
    }

    @Test
    void listenerRequiresAClientCertificate() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Throwable> listenerFailure = CompletableFuture.supplyAsync(() -> {
                try (SSLSocket accepted = listener.serverSide(server.accept())) {
                    accepted.startHandshake();
                    accepted.getInputStream().read();
                    return null;
                } catch (IOException e) {
                    return e;
                }
            });

            // A TLS 1.3 client with no key material at all (and trusting the listener's key).
            SSLContext anonymous = SSLContext.getInstance("TLSv1.3");
            anonymous.init(null, new javax.net.ssl.TrustManager[] {
                    new PinnedServerTrustManager(listener.identity().fingerprint())}, null);
            Socket raw = connect(server);
            try (SSLSocket client = (SSLSocket) anonymous.getSocketFactory().createSocket(raw, "localhost", raw.getPort(), true)) {
                javax.net.ssl.SSLParameters parameters = client.getSSLParameters();
                parameters.setApplicationProtocols(new String[] {ProtocolConstants.ALPN});
                client.setSSLParameters(parameters);
                try {
                    client.startHandshake();
                    client.getOutputStream().write(1);
                    client.getInputStream().read();
                } catch (IOException expected) {
                    // the listener aborts
                }
            }
            assertThat(listenerFailure.get(5, TimeUnit.SECONDS)).isInstanceOf(SSLHandshakeException.class);
        }
    }

    @Test
    void clientOfferingNoApplicationProtocolFailsTheHandshakeItself() throws Exception {
        assertListenerHandshakeFailsFor(new String[0]);
    }

    @Test
    void clientOfferingOnlyAnotherApplicationProtocolFailsTheHandshakeItself() throws Exception {
        assertListenerHandshakeFailsFor(new String[] {"h2", "peerlink/1"});
    }

    @Test
    void connectingSideRefusesAServerThatDoesNotSelectThePeerLinkProtocol() throws Exception {
        // The listener's genuine key, but a server that ignores ALPN altogether.
        SSLContext plain = SSLContext.getInstance("TLSv1.3");
        plain.init(listener.identity().keyManagers(), null, null);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture.runAsync(() -> {
                try (Socket accepted = server.accept();
                     SSLSocket tls = (SSLSocket) plain.getSocketFactory().createSocket(accepted, null, accepted.getPort(), true)) {
                    tls.setUseClientMode(false);
                    tls.startHandshake();
                } catch (IOException e) {
                    // the initiator aborts
                }
            });
            try (SSLSocket client = initiator.clientSide(connect(server), listener.identity().fingerprint())) {
                assertThatThrownBy(client::startHandshake).isInstanceOf(SSLHandshakeException.class)
                        .satisfies(e -> assertThat(PeerTls.isFingerprintMismatch(e)).isFalse());
            }
        }
    }

    /** A client with a valid certificate but the given ALPN offer: the listener's handshake must fail. */
    private void assertListenerHandshakeFailsFor(String[] protocols) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Throwable> listenerFailure = CompletableFuture.supplyAsync(() -> {
                try (SSLSocket accepted = listener.serverSide(server.accept())) {
                    accepted.startHandshake(); // no later check: the handshake itself must refuse
                    return null;
                } catch (IOException e) {
                    return e;
                }
            });
            // A non-PeerLink client: the initiator's certificate, but it checks nothing about the server,
            // so only the listener can refuse.
            SSLContext probing = SSLContext.getInstance("TLSv1.3");
            probing.init(initiator.identity().keyManagers(), new javax.net.ssl.TrustManager[] {new AcceptAnyServer()}, null);
            Socket raw = connect(server);
            try (SSLSocket client = (SSLSocket) probing.getSocketFactory().createSocket(raw, "localhost", raw.getPort(), true)) {
                javax.net.ssl.SSLParameters parameters = client.getSSLParameters();
                parameters.setApplicationProtocols(protocols);
                client.setSSLParameters(parameters);
                try {
                    client.startHandshake();
                    client.getInputStream().read();
                } catch (IOException expected) {
                    // the listener aborts the handshake
                }
            }
            assertThat(listenerFailure.get(5, TimeUnit.SECONDS)).isInstanceOf(SSLHandshakeException.class);
        }
    }

    @Test
    void handshakeWithoutPeerLinkAlpnIsRefused() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Throwable> listenerFailure = CompletableFuture.supplyAsync(() -> {
                try (SSLSocket accepted = listener.serverSide(server.accept())) {
                    accepted.startHandshake();
                    PeerTls.requireApplicationProtocol(accepted);
                    return null;
                } catch (IOException e) {
                    return e;
                }
            });
            try (SSLSocket client = initiator.clientSide(connect(server), listener.identity().fingerprint())) {
                javax.net.ssl.SSLParameters parameters = client.getSSLParameters();
                parameters.setApplicationProtocols(new String[] {"http/1.1"});
                client.setSSLParameters(parameters);
                try {
                    client.startHandshake();
                    client.getInputStream().read();
                } catch (IOException expected) {
                    // no_application_protocol alert
                }
            }
            assertThat(listenerFailure.get(5, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
        }
    }

    private static Socket connect(ServerSocket server) throws IOException {
        Socket socket = new Socket();
        socket.connect(server.getLocalSocketAddress(), 2000);
        socket.setSoTimeout(5000);
        return socket;
    }

    /** Test-only client trust manager that accepts any server, to probe the listener's own checks. */
    private static final class AcceptAnyServer extends javax.net.ssl.X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
