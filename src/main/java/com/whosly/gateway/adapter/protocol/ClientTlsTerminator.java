package com.whosly.gateway.adapter.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Objects;

/**
 * Protocol-agnostic TLS termination for the <em>client</em> leg.
 *
 * <p>When enabled, each accepted TCP socket is wrapped with an {@link SSLSocket}
 * in server mode using a configured keystore. After the handshake, adapters see
 * cleartext protocol framing on the decrypted streams — the same path MySQL,
 * PostgreSQL, and future Oracle / SQL Server adapters already use.</p>
 *
 * <p>This is stunnel-style TCP TLS (client speaks TLS from the first byte). It is
 * <strong>not</strong> MySQL capability-flag SSL or PostgreSQL {@code SSLRequest}
 * negotiation; those remain opaque-tunnel / reject behaviour when terminate is
 * off. Backend connections keep using the existing {@link BackendProvider}
 * (typically cleartext to a local Docker target).</p>
 */
public final class ClientTlsTerminator {

    private static final Logger log = LoggerFactory.getLogger(ClientTlsTerminator.class);

    private final SSLContext sslContext;

    private ClientTlsTerminator(SSLContext sslContext) {
        this.sslContext = sslContext;
    }

    /** TLS terminate disabled — {@link #wrapAcceptedClient(Socket)} is identity. */
    public static ClientTlsTerminator disabled() {
        return new ClientTlsTerminator(null);
    }

    /**
     * Loads a JKS/PKCS12 keystore and builds a server-side TLS terminator.
     *
     * @param keystorePath     path to the keystore file
     * @param keystorePassword keystore (and key) password
     * @param keystoreType     e.g. {@code PKCS12} or {@code JKS}; blank defaults to
     *                         {@link KeyStore#getDefaultType()}
     */
    public static ClientTlsTerminator fromKeyStore(Path keystorePath, char[] keystorePassword,
                                                   String keystoreType)
            throws GeneralSecurityException, IOException {
        Objects.requireNonNull(keystorePath, "keystorePath must not be null");
        Objects.requireNonNull(keystorePassword, "keystorePassword must not be null");
        String type = (keystoreType == null || keystoreType.isBlank())
                ? KeyStore.getDefaultType() : keystoreType;
        KeyStore keyStore = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(keystorePath)) {
            keyStore.load(in, keystorePassword);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keystorePassword);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        return new ClientTlsTerminator(context);
    }

    /** True when a keystore was loaded and client sockets will be wrapped. */
    public boolean isEnabled() {
        return sslContext != null;
    }

    /**
     * When enabled, wraps {@code accepted} as a server {@link SSLSocket} and
     * completes the handshake; otherwise returns {@code accepted} unchanged.
     */
    public Socket wrapAcceptedClient(Socket accepted) throws IOException {
        if (sslContext == null) {
            return accepted;
        }
        Objects.requireNonNull(accepted, "accepted must not be null");
        SSLSocketFactory factory = sslContext.getSocketFactory();
        SSLSocket sslSocket = (SSLSocket) factory.createSocket(
                accepted,
                accepted.getInetAddress() != null ? accepted.getInetAddress().getHostAddress() : null,
                accepted.getPort(),
                true);
        sslSocket.setUseClientMode(false);
        sslSocket.startHandshake();
        log.debug("TLS terminated for client {}", accepted.getRemoteSocketAddress());
        return sslSocket;
    }
}
