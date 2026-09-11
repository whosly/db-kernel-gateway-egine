package com.whosly.gateway.adapter.protocol;

import com.whosly.gateway.adapter.mysql.MySQLFrameCodec;
import com.whosly.gateway.adapter.mysql.MySqlGatewayErrorMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DuplexRelayTest {

    @Test
    void relaysBytesInBothDirections() throws Exception {
        try (SocketPair left = SocketPair.open();
             SocketPair right = SocketPair.open()) {
            ExecutorService executorService = Executors.newSingleThreadExecutor();
            DuplexRelay relay = new DuplexRelay("test");
            Future<?> relayFuture = executorService.submit(() -> {
                try {
                    relay.relay(left.serverSide, right.serverSide);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            byte[] clientPayload = new byte[]{0x16, 0x03, 0x01, 0x00, 0x2a, 0x00, (byte) 0xff};
            left.clientSide.getOutputStream().write(clientPayload);
            left.clientSide.getOutputStream().flush();

            assertThat(readExact(right.clientSide.getInputStream(), clientPayload.length))
                    .isEqualTo(clientPayload);

            byte[] targetPayload = new byte[]{0x00, 0x00, 0x00, 0x08, 0x04, (byte) 0xd2, 0x16, 0x2f};
            right.clientSide.getOutputStream().write(targetPayload);
            right.clientSide.getOutputStream().flush();

            assertThat(readExact(left.clientSide.getInputStream(), targetPayload.length))
                    .isEqualTo(targetPayload);

            left.clientSide.close();
            right.clientSide.close();
            relayFuture.get(2, TimeUnit.SECONDS);
            executorService.shutdownNow();
        }
    }

    @Test
    void writesBytesUnchangedWhenThePipelineDoesNotRewrite() throws Exception {
        try (SocketPair left = SocketPair.open();
             SocketPair right = SocketPair.open()) {
            ExecutorService executorService = Executors.newSingleThreadExecutor();
            try {
                List<Integer> observedLengths = new CopyOnWriteArrayList<>();
                MessageInterceptor observer =
                        Interceptors.observe(message -> observedLengths.add(message.originalLength()));
                DuplexRelay relay = new DuplexRelay("observe-test", MessagePipeline.of(observer));

                Future<?> relayFuture = executorService.submit(() -> {
                    try {
                        relay.relay(left.serverSide, right.serverSide);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                byte[] payload = new byte[]{0x16, 0x03, 0x01, 0x00, 0x2a, 0x00, (byte) 0xff};
                left.clientSide.getOutputStream().write(payload);
                left.clientSide.getOutputStream().flush();

                assertThat(readExact(right.clientSide.getInputStream(), payload.length)).isEqualTo(payload);
                assertThat(observedLengths).isNotEmpty();

                left.clientSide.close();
                right.clientSide.close();
                relayFuture.get(2, TimeUnit.SECONDS);
            } finally {
                executorService.shutdownNow();
            }
        }
    }

    @Test
    void writesTheReplacementPayloadProducedByARewriteInterceptor() throws Exception {
        try (SocketPair left = SocketPair.open();
             SocketPair right = SocketPair.open()) {
            ExecutorService executorService = Executors.newSingleThreadExecutor();
            try {
                MessageInterceptor upperCase = Interceptors.rewrite(message -> {
                    String text = new String(message.originalBytes(), message.originalOffset(),
                            message.originalLength(), StandardCharsets.US_ASCII);
                    return message.withReplacement(
                            text.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
                });
                DuplexRelay relay = new DuplexRelay("rewrite-test", MessagePipeline.of(upperCase));

                Future<?> relayFuture = executorService.submit(() -> {
                    try {
                        relay.relay(left.serverSide, right.serverSide);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                byte[] payload = "select 1".getBytes(StandardCharsets.US_ASCII);
                left.clientSide.getOutputStream().write(payload);
                left.clientSide.getOutputStream().flush();

                byte[] received = readExact(right.clientSide.getInputStream(), payload.length);
                assertThat(new String(received, StandardCharsets.US_ASCII)).isEqualTo("SELECT 1");

                left.clientSide.close();
                right.clientSide.close();
                relayFuture.get(2, TimeUnit.SECONDS);
            } finally {
                executorService.shutdownNow();
            }
        }
    }

    @Test
    void answersDeniedTrafficWithProtocolNativeError() throws Exception {
        try (SocketPair left = SocketPair.open();
             SocketPair right = SocketPair.open()) {
            ExecutorService executorService = Executors.newSingleThreadExecutor();
            try {
                TrafficInspector denyAll = TrafficDecision::deny;
                ProtocolErrorResponder responder = new ProtocolErrorResponder(
                        new MySQLFrameCodec(), new MySqlGatewayErrorMapper());
                DuplexRelay relay = new DuplexRelay("deny-test", denyAll, responder);

                Future<?> relayFuture = executorService.submit(() -> {
                    try {
                        relay.relay(left.serverSide, right.serverSide);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                left.clientSide.getOutputStream().write("select 1".getBytes(StandardCharsets.US_ASCII));
                left.clientSide.getOutputStream().flush();

                byte[] received = left.clientSide.getInputStream().readAllBytes();

                assertThat(received.length).isGreaterThan(12);
                assertThat(received[4] & 0xFF).isEqualTo(0xFF);
                assertThat(new String(received, 7, 6, StandardCharsets.US_ASCII)).isEqualTo("#42000");
                relayFuture.get(2, TimeUnit.SECONDS);
            } finally {
                executorService.shutdownNow();
            }
        }
    }

    private static byte[] readExact(InputStream inputStream, int length) throws Exception {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = inputStream.read(bytes, offset, length - offset);
            if (count < 0) {
                throw new AssertionError("Unexpected end of stream");
            }
            offset += count;
        }
        return bytes;
    }

    private static final class SocketPair implements AutoCloseable {
        private final Socket clientSide;
        private final Socket serverSide;

        private SocketPair(Socket clientSide, Socket serverSide) {
            this.clientSide = clientSide;
            this.serverSide = serverSide;
        }

        private static SocketPair open() throws Exception {
            try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                Socket client = new Socket(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
                Socket server = serverSocket.accept();
                return new SocketPair(client, server);
            }
        }

        @Override
        public void close() throws Exception {
            clientSide.close();
            serverSide.close();
        }
    }
}
