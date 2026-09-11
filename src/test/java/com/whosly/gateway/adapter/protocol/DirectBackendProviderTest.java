package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;

class DirectBackendProviderTest {

    @Test
    void acquiresAConnectedSocketAndClosesItOnRelease() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            DirectBackendProvider provider = new DirectBackendProvider(
                    InetAddress.getLoopbackAddress().getHostAddress(), serverSocket.getLocalPort(), 2000);

            Socket connection = provider.acquire();
            try (Socket accepted = serverSocket.accept()) {
                assertThat(connection.isConnected()).isTrue();
                assertThat(accepted.isConnected()).isTrue();
            }

            provider.release(connection);

            assertThat(connection.isClosed()).isTrue();
        }
    }

    @Test
    void releaseToleratesNullAndRepeatedCalls() {
        DirectBackendProvider provider = new DirectBackendProvider("localhost", 1, 100);

        provider.release(null);

        Socket socket = new Socket();
        provider.release(socket);
        provider.release(socket);

        assertThat(socket.isClosed()).isTrue();
    }
}
