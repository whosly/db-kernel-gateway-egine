package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for stunnel-style client TLS terminate (shared by every proxy-db-type).
 * Keystore under test resources uses a known password — never production secrets.
 */
class ClientTlsTerminatorTest {

    private static final Path KEYSTORE = Path.of("src/test/resources/tls/gateway-test.p12");
    private static final char[] PASSWORD = "changeit".toCharArray();

    @Test
    void disabledTerminatorReturnsSameSocket() throws Exception {
        ClientTlsTerminator terminator = ClientTlsTerminator.disabled();
        assertThat(terminator.isEnabled()).isFalse();
        try (Socket socket = new Socket()) {
            assertThat(terminator.wrapAcceptedClient(socket)).isSameAs(socket);
        }
    }

    @Test
    void loadsKeystoreAndCompletesHandshakeWithCleartextBytePath() throws Exception {
        ClientTlsTerminator terminator = ClientTlsTerminator.fromKeyStore(KEYSTORE, PASSWORD, "PKCS12");
        assertThat(terminator.isEnabled()).isTrue();

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<String> seenByServer = CompletableFuture.supplyAsync(() -> {
                try (Socket accepted = server.accept();
                     Socket tls = terminator.wrapAcceptedClient(accepted)) {
                    assertThat(tls).isInstanceOf(SSLSocket.class);
                    byte[] buf = new byte[64];
                    int n = tls.getInputStream().read(buf);
                    tls.getOutputStream().write("PONG".getBytes(StandardCharsets.UTF_8));
                    tls.getOutputStream().flush();
                    return new String(buf, 0, n, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            SSLSocketFactory clientFactory = trustingClientFactory();
            try (SSLSocket client = (SSLSocket) clientFactory.createSocket(
                    "127.0.0.1", server.getLocalPort())) {
                client.setUseClientMode(true);
                client.startHandshake();
                client.getOutputStream().write("PING".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                byte[] buf = new byte[64];
                int n = client.getInputStream().read(buf);
                assertThat(new String(buf, 0, n, StandardCharsets.UTF_8)).isEqualTo("PONG");
            }

            assertThat(seenByServer.get(5, TimeUnit.SECONDS)).isEqualTo("PING");
        }
    }

    @Test
    void fromKeyStoreRejectsMissingFile() {
        assertThatThrownBy(() -> ClientTlsTerminator.fromKeyStore(
                Path.of("src/test/resources/tls/missing.p12"), PASSWORD, "PKCS12"))
                .isInstanceOf(Exception.class);
    }

    private static SSLSocketFactory trustingClientFactory() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAll, new java.security.SecureRandom());
        return context.getSocketFactory();
    }
}
