package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.sqlserver.TdsPacket;
import com.whosly.gateway.adapter.sqlserver.TdsPacketType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SQLServerProtocolAdapterTest {

    @Test
    void createsSqlServerProtocolAdapter() {
        SQLServerProtocolAdapter adapter = new SQLServerProtocolAdapter();

        assertThat(adapter.getProtocolName()).isEqualTo("SQLServer");
        assertThat(adapter.getDefaultPort()).isEqualTo(14330);
        assertThat(adapter.isRunning()).isFalse();
    }

    @Test
    void handleClientConnectionTransparentlyRelaysTargetAndClientBytes() throws Exception {
        try (ServerSocket targetServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocketPair clientPair = SocketPair.open();
             ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            SQLServerProtocolAdapter adapter = new SQLServerProtocolAdapter();
            adapter.setTargetHost(InetAddress.getLoopbackAddress().getHostAddress());
            adapter.setTargetPort(targetServer.getLocalPort());

            Future<?> adapterFuture = executorService.submit(() -> adapter.handleClientConnection(clientPair.serverSide));
            Future<byte[]> backendObservedClientBytes = executorService.submit(() -> {
                try (Socket targetSocket = targetServer.accept()) {
                    byte[] preloginResponse = tdsPacket(TdsPacketType.PRELOGIN,
                            TdsPacket.STATUS_END_OF_MESSAGE, new byte[]{1, 2, 3});
                    targetSocket.getOutputStream().write(preloginResponse);
                    targetSocket.getOutputStream().flush();
                    return readExact(targetSocket.getInputStream(), tdsPacketLength("select 1"));
                }
            });

            readExact(clientPair.clientSide.getInputStream(), tdsPacketLengthBytes(new byte[]{1, 2, 3}));

            byte[] query = tdsPacket(TdsPacketType.SQL_BATCH, TdsPacket.STATUS_END_OF_MESSAGE,
                    "select 1".getBytes(StandardCharsets.UTF_16LE));
            clientPair.clientSide.getOutputStream().write(query);
            clientPair.clientSide.getOutputStream().flush();

            assertThat(backendObservedClientBytes.get(2, TimeUnit.SECONDS)).isEqualTo(query);

            clientPair.clientSide.close();
            adapterFuture.get(2, TimeUnit.SECONDS);
            executorService.shutdownNow();
        }
    }

    @Test
    void handleClientConnectionObservesSqlBatchEvents() throws Exception {
        try (ServerSocket targetServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocketPair clientPair = SocketPair.open();
             ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            SQLServerProtocolAdapter adapter = new SQLServerProtocolAdapter();
            adapter.setTargetHost(InetAddress.getLoopbackAddress().getHostAddress());
            adapter.setTargetPort(targetServer.getLocalPort());
            List<DatabaseTrafficEvent> observedEvents = new CopyOnWriteArrayList<>();
            adapter.setDatabaseTrafficObserver(observedEvents::add);

            Future<?> adapterFuture = executorService.submit(() -> adapter.handleClientConnection(clientPair.serverSide));
            Future<byte[]> backendObservedQuery = executorService.submit(() -> {
                try (Socket targetSocket = targetServer.accept()) {
                    return readExact(targetSocket.getInputStream(), tdsPacketLength("select * from account"));
                }
            });

            byte[] query = tdsPacket(TdsPacketType.SQL_BATCH, TdsPacket.STATUS_END_OF_MESSAGE,
                    "select * from account".getBytes(StandardCharsets.UTF_16LE));
            clientPair.clientSide.getOutputStream().write(query);
            clientPair.clientSide.getOutputStream().flush();

            assertThat(backendObservedQuery.get(2, TimeUnit.SECONDS)).isEqualTo(query);
            assertEventuallyObservedStatement(observedEvents, "select * from account");

            clientPair.clientSide.close();
            adapterFuture.get(2, TimeUnit.SECONDS);
            executorService.shutdownNow();
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

    private static byte[] tdsPacket(TdsPacketType type, int status, byte[] payload) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        int length = payload.length + TdsPacket.HEADER_LENGTH;
        outputStream.write(type.getCode());
        outputStream.write(status);
        outputStream.write((length >> 8) & 0xFF);
        outputStream.write(length & 0xFF);
        outputStream.write(0);
        outputStream.write(0);
        outputStream.write(1);
        outputStream.write(0);
        outputStream.writeBytes(payload);
        return outputStream.toByteArray();
    }

    private static int tdsPacketLength(String statement) {
        return TdsPacket.HEADER_LENGTH + statement.getBytes(StandardCharsets.UTF_16LE).length;
    }

    private static int tdsPacketLengthBytes(byte[] payload) {
        return TdsPacket.HEADER_LENGTH + payload.length;
    }

    private static void assertEventuallyObservedStatement(List<DatabaseTrafficEvent> observedEvents,
                                                          String statement) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (observedEvents.stream().anyMatch(event -> statement.equals(event.getStatement()))) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(observedEvents)
                .extracting(DatabaseTrafficEvent::getStatement)
                .contains(statement);
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
