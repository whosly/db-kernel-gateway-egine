package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailoverBackendProviderTest {
    @Test
    void connectsToFirstReachableEndpoint() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            FailoverBackendProvider provider = new FailoverBackendProvider(
                    List.of(new BackendEndpoint("127.0.0.1", 1), new BackendEndpoint("127.0.0.1", port)), 500);
            Socket socket = provider.acquire();
            assertThat(socket.isConnected()).isTrue();
            provider.release(socket);
        }
    }
    @Test
    void failsWhenAllEndpointsUnreachable() {
        FailoverBackendProvider provider = new FailoverBackendProvider(
                List.of(new BackendEndpoint("127.0.0.1", 1)), 200);
        assertThatThrownBy(provider::acquire).isInstanceOf(IOException.class);
    }
}
