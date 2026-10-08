package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.LoopbackSockets;
import com.whosly.gateway.adapter.protocol.ObservationConfidence;
import com.whosly.gateway.adapter.protocol.ProtocolConnectionState;
import com.whosly.gateway.adapter.protocol.PooledBackendProvider;
import com.whosly.gateway.adapter.protocol.DirectBackendProvider;
import com.whosly.gateway.adapter.protocol.SessionDirtiness;
import com.whosly.gateway.adapter.protocol.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgreSQLBackendSessionResetTest {

    private static final PostgreSQLFrameCodec CODEC = new PostgreSQLFrameCodec();

    @Test
    void returnsTrueWhenReadyForQueryFollowsCommandComplete() throws Exception {
        try (LoopbackSockets.Pair pair = LoopbackSockets.open()) {
            Thread server = startThread(() -> {
                try {
                    // Read Query message: type + length + payload
                    byte[] header = LoopbackSockets.readExact(pair.serverSide().getInputStream(), 5);
                    assertThat((char) header[0]).isEqualTo('Q');
                    int length = ((header[1] & 0xFF) << 24) | ((header[2] & 0xFF) << 16)
                            | ((header[3] & 0xFF) << 8) | (header[4] & 0xFF);
                    LoopbackSockets.readExact(pair.serverSide().getInputStream(), length - 4);
                    OutputStream out = pair.serverSide().getOutputStream();
                    out.write(CODEC.packet('C', "DISCARD ALL\0".getBytes(StandardCharsets.UTF_8)));
                    out.write(CODEC.packet('Z', new byte[]{'I'}));
                    out.flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            PostgreSQLBackendSessionReset reset = new PostgreSQLBackendSessionReset();
            assertThat(reset.reset(pair.clientSide(), reusableSnapshot())).isTrue();
            server.join();
        }
    }

    @Test
    void returnsFalseWhenErrorResponseBeforeReadyForQuery() throws Exception {
        try (LoopbackSockets.Pair pair = LoopbackSockets.open()) {
            Thread server = startThread(() -> {
                try {
                    byte[] header = LoopbackSockets.readExact(pair.serverSide().getInputStream(), 5);
                    int length = ((header[1] & 0xFF) << 24) | ((header[2] & 0xFF) << 16)
                            | ((header[3] & 0xFF) << 8) | (header[4] & 0xFF);
                    LoopbackSockets.readExact(pair.serverSide().getInputStream(), length - 4);
                    OutputStream out = pair.serverSide().getOutputStream();
                    // Minimal ErrorResponse fields then ReadyForQuery
                    out.write(CODEC.packet('E', "SERROR\0C26000\0Mfail\0\0".getBytes(StandardCharsets.UTF_8)));
                    out.write(CODEC.packet('Z', new byte[]{'I'}));
                    out.flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            PostgreSQLBackendSessionReset reset = new PostgreSQLBackendSessionReset();
            assertThat(reset.reset(pair.clientSide(), reusableSnapshot())).isFalse();
            server.join();
        }
    }

    @Test
    void throwsWhenPeerClosesDuringReset() throws Exception {
        try (LoopbackSockets.Pair pair = LoopbackSockets.open()) {
            pair.serverSide().close();
            PostgreSQLBackendSessionReset reset = new PostgreSQLBackendSessionReset();
            assertThatThrownBy(() -> reset.reset(pair.clientSide(), reusableSnapshot()))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void pooledProviderClosesSocketWhenResetFails() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread acceptor = startThread(() -> {
                try (var backend = server.accept()) {
                    byte[] header = LoopbackSockets.readExact(backend.getInputStream(), 5);
                    int length = ((header[1] & 0xFF) << 24) | ((header[2] & 0xFF) << 16)
                            | ((header[3] & 0xFF) << 8) | (header[4] & 0xFF);
                    LoopbackSockets.readExact(backend.getInputStream(), length - 4);
                    OutputStream out = backend.getOutputStream();
                    out.write(CODEC.packet('E', "SERROR\0C26000\0Mfail\0\0".getBytes(StandardCharsets.UTF_8)));
                    out.write(CODEC.packet('Z', new byte[]{'I'}));
                    out.flush();
                    Thread.sleep(200);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            DirectBackendProvider factory = new DirectBackendProvider(
                    "127.0.0.1", server.getLocalPort(), 2000);
            try (PooledBackendProvider pooled = new PooledBackendProvider(
                    factory, 2, new PostgreSQLBackendSessionReset())) {
                var socket = pooled.acquire();
                pooled.release(socket, reusableSnapshot());
                assertThat(pooled.idleCount()).isZero();
                assertThat(socket.isClosed()).isTrue();
            }
            acceptor.join();
        }
    }

    private static SessionSnapshot reusableSnapshot() {
        return new SessionSnapshot(
                "PostgreSQL", "s1", ProtocolConnectionState.READY, ObservationConfidence.CONFIRMED,
                false, Optional.empty(), Optional.empty(), SessionDirtiness.clean(),
                Instant.now(), Instant.now());
    }

    private static Thread startThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "pg-reset-test");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
