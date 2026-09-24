package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.ProbedHandshake;
import com.whosly.gateway.adapter.protocol.RoutingContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PostgreSQLStartupRoutingTest {

    @Test
    void fromStartupPayloadExtractsUserAndDatabase() {
        byte[] startup = startupMessage("user", "alice", "database", "app_a", "application_name", "gw");
        // payload = bytes after 4-byte length
        byte[] payload = new byte[startup.length - 4];
        System.arraycopy(startup, 4, payload, 0, payload.length);

        assertThat(PostgreSQLStartupRouting.fromStartupPayload(payload))
                .isEqualTo(RoutingContext.of("alice", "app_a"));
    }

    @Test
    void fromStartupPayloadUserOnly() {
        byte[] startup = startupMessage("user", "bob");
        byte[] payload = new byte[startup.length - 4];
        System.arraycopy(startup, 4, payload, 0, payload.length);

        assertThat(PostgreSQLStartupRouting.fromStartupPayload(payload))
                .isEqualTo(RoutingContext.ofUsername("bob"));
    }

    @Test
    void probeReadsStartupAndReturnsNonEmptyContextWithReplayBytes() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<ProbedHandshake> probedFuture = executor.submit(() -> {
                try (Socket accepted = server.accept()) {
                    return new PostgreSQLStartupRouting(2000).probe(accepted);
                }
            });

            byte[] startup = startupMessage("user", "alice", "database", "shop");
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.getOutputStream().write(startup);
                client.getOutputStream().flush();

                ProbedHandshake probed = probedFuture.get(3, TimeUnit.SECONDS);
                assertThat(probed.context()).isEqualTo(RoutingContext.of("alice", "shop"));
                assertThat(probed.hasReplay()).isTrue();
                assertThat(probed.replayToBackend()).isEqualTo(startup);
            }
            executor.shutdownNow();
        }
    }

    @Test
    void probeSslRequestYieldsEmptyContextButReplaysBytes() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<ProbedHandshake> probedFuture = executor.submit(() -> {
                try (Socket accepted = server.accept()) {
                    return new PostgreSQLStartupRouting(2000).probe(accepted);
                }
            });

            byte[] ssl = new byte[]{0x00, 0x00, 0x00, 0x08, 0x04, (byte) 0xD2, 0x16, 0x2F};
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.getOutputStream().write(ssl);
                client.getOutputStream().flush();

                ProbedHandshake probed = probedFuture.get(3, TimeUnit.SECONDS);
                assertThat(probed.context().isEmpty()).isTrue();
                assertThat(probed.replayToBackend()).isEqualTo(ssl);
            }
            executor.shutdownNow();
        }
    }

    private static byte[] startupMessage(String... keyValues) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(intBytes(196608));
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            body.writeBytes(cstring(keyValues[index]));
            body.writeBytes(cstring(keyValues[index + 1]));
        }
        body.write(0);
        byte[] payload = body.toByteArray();
        return concat(intBytes(payload.length + 4), payload);
    }

    private static byte[] intBytes(int value) {
        return new byte[]{
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
    }

    private static byte[] cstring(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] cstring = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, cstring, 0, bytes.length);
        return cstring;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = new byte[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }
}
