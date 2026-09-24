package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.LoopbackSockets;
import com.whosly.gateway.adapter.protocol.ObservationConfidence;
import com.whosly.gateway.adapter.protocol.ProtocolConnectionState;
import com.whosly.gateway.adapter.protocol.SessionDirtiness;
import com.whosly.gateway.adapter.protocol.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MySqlBackendSessionResetTest {

    @Test
    void returnsTrueWhenServerAnswersOk() throws Exception {
        try (LoopbackSockets.Pair pair = LoopbackSockets.open()) {
            Thread server = startThread(() -> {
                try {
                    byte[] command = LoopbackSockets.readExact(pair.serverSide().getInputStream(), 5);
                    assertThat(command[4] & 0xFF).isEqualTo(MySQLCommandType.COM_RESET_CONNECTION.getCode());
                    // OK packet: payload 0x00 + minimal body
                    pair.serverSide().getOutputStream().write(
                            MySQLTestFrames.packet(new byte[]{0x00, 0x00, 0x00, 0x02, 0x00}, 1));
                    pair.serverSide().getOutputStream().flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            MySqlBackendSessionReset reset = new MySqlBackendSessionReset();
            assertThat(reset.reset(pair.clientSide(), reusableSnapshot())).isTrue();
            server.join();
        }
    }

    @Test
    void returnsFalseWhenServerAnswersErr() throws Exception {
        try (LoopbackSockets.Pair pair = LoopbackSockets.open()) {
            Thread server = startThread(() -> {
                try {
                    LoopbackSockets.readExact(pair.serverSide().getInputStream(), 5);
                    // ERR packet header 0xFF
                    byte[] err = new byte[]{(byte) 0xFF, 0x15, 0x04, '#', 'H', 'Y', '0', '0', '0',
                            'e', 'r', 'r'};
                    pair.serverSide().getOutputStream().write(MySQLTestFrames.packet(err, 1));
                    pair.serverSide().getOutputStream().flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            MySqlBackendSessionReset reset = new MySqlBackendSessionReset();
            assertThat(reset.reset(pair.clientSide(), reusableSnapshot())).isFalse();
            server.join();
        }
    }

    @Test
    void throwsWhenPeerClosesDuringReset() throws Exception {
        try (LoopbackSockets.Pair pair = LoopbackSockets.open()) {
            pair.serverSide().close();
            MySqlBackendSessionReset reset = new MySqlBackendSessionReset();
            assertThatThrownBy(() -> reset.reset(pair.clientSide(), reusableSnapshot()))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void pooledProviderClosesSocketWhenResetReturnsFalse() throws Exception {
        AtomicReference<byte[]> seen = new AtomicReference<>();
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Thread acceptor = startThread(() -> {
                try (var backend = server.accept()) {
                    byte[] cmd = LoopbackSockets.readExact(backend.getInputStream(), 5);
                    seen.set(cmd);
                    OutputStream out = backend.getOutputStream();
                    out.write(MySQLTestFrames.packet(new byte[]{(byte) 0xFF, 0x01, 0x00, '#', 'H', 'Y', '0', '0', '0', 'x'}, 1));
                    out.flush();
                    // keep open briefly so client can read
                    Thread.sleep(200);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            var factory = new com.whosly.gateway.adapter.protocol.DirectBackendProvider(
                    "127.0.0.1", server.getLocalPort(), 2000);
            try (var pooled = new com.whosly.gateway.adapter.protocol.PooledBackendProvider(
                    factory, 2, new MySqlBackendSessionReset())) {
                var socket = pooled.acquire();
                pooled.release(socket, reusableSnapshot());
                assertThat(pooled.idleCount()).isZero();
                assertThat(socket.isClosed()).isTrue();
            }
            acceptor.join();
            assertThat(seen.get()).isNotNull();
            assertThat(seen.get()[4] & 0xFF).isEqualTo(MySQLCommandType.COM_RESET_CONNECTION.getCode());
        }
    }

    private static SessionSnapshot reusableSnapshot() {
        return new SessionSnapshot(
                "MySQL", "s1", ProtocolConnectionState.READY, ObservationConfidence.CONFIRMED,
                false, Optional.empty(), Optional.empty(), SessionDirtiness.clean(),
                Instant.now(), Instant.now());
    }

    private static Thread startThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "mysql-reset-test");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
