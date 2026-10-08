package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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

    @Test
    void skipsRecentlyFailedEndpointWhileHealthyCandidateExists() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int livePort = server.getLocalPort();
            BackendEndpoint dead = new BackendEndpoint("127.0.0.1", 1);
            BackendEndpoint live = new BackendEndpoint("127.0.0.1", livePort);
            AtomicLong clock = new AtomicLong(1_000L);
            GatewayRuntimeMetrics metrics = new GatewayRuntimeMetrics();
            FailoverBackendProvider provider = new FailoverBackendProvider(
                    List.of(dead, live), 200, metrics, 5_000L, clock::get);

            Socket first = provider.acquire();
            assertThat(first.isConnected()).isTrue();
            assertThat(provider.isMarkedUnhealthy(dead)).isTrue();
            assertThat(metrics.backendFailovers()).isEqualTo(1);
            provider.release(first);

            // Next acquire must not wait on the dead endpoint again during cooldown.
            long before = System.nanoTime();
            Socket second = provider.acquire();
            long elapsedMs = (System.nanoTime() - before) / 1_000_000L;
            assertThat(second.isConnected()).isTrue();
            assertThat(elapsedMs).isLessThan(150);
            provider.release(second);
        }
    }

    @Test
    void retriesUnhealthyEndpointAfterCooldownExpires() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int livePort = server.getLocalPort();
            BackendEndpoint dead = new BackendEndpoint("127.0.0.1", 1);
            BackendEndpoint live = new BackendEndpoint("127.0.0.1", livePort);
            AtomicLong clock = new AtomicLong(1_000L);
            FailoverBackendProvider provider = new FailoverBackendProvider(
                    List.of(dead, live), 200, GatewayRuntimeMetrics.noop(), 100L, clock::get);

            provider.release(provider.acquire());
            assertThat(provider.isMarkedUnhealthy(dead)).isTrue();

            clock.set(1_000L + 101L);
            assertThat(provider.isMarkedUnhealthy(dead)).isFalse();
        }
    }
}
