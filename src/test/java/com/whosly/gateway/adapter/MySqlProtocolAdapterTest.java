package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.mysql.MySQLCommandType;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
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

class MySqlProtocolAdapterTest {

    @Test
    void testProtocolAdapterCreation() {
        MySqlProtocolAdapter adapter = new MySqlProtocolAdapter();
        
        assertThat(adapter).isNotNull();
        assertThat(adapter.getProtocolName()).isEqualTo("MySQL");
        assertThat(adapter.getDefaultPort()).isEqualTo(3307); // 更新为新的默认端口
        assertThat(adapter.isRunning()).isFalse();
    }

    @Test
    void testStartAndStop() {
        MySqlProtocolAdapter adapter = new MySqlProtocolAdapter();
        
        // Start the adapter
        adapter.start();
        // Note: In a real test environment, we might not be able to actually start the server
        // So we'll just test that the method doesn't throw an exception
        
        // Stop the adapter
        adapter.stop();
        // Same here, we're just testing that the method doesn't throw an exception
    }
    
    @Test
    void handleClientConnectionTransparentlyRelaysTargetAndClientBytes() throws Exception {
        try (ServerSocket targetServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocketPair clientPair = SocketPair.open()) {
            ExecutorService executorService = Executors.newFixedThreadPool(2);
            MySqlProtocolAdapter adapter = new MySqlProtocolAdapter();
            adapter.setTargetHost(InetAddress.getLoopbackAddress().getHostAddress());
            adapter.setTargetPort(targetServer.getLocalPort());

            Future<?> adapterFuture = executorService.submit(() -> adapter.handleClientConnection(clientPair.serverSide));
            Future<byte[]> backendObservedClientBytes = executorService.submit(() -> {
                try (Socket targetSocket = targetServer.accept()) {
                    byte[] handshake = "mysql-target-handshake".getBytes(StandardCharsets.UTF_8);
                    targetSocket.getOutputStream().write(handshake);
                    targetSocket.getOutputStream().flush();

                    byte[] received = readExact(targetSocket.getInputStream(), 9);

                    byte[] ok = "mysql-target-ok".getBytes(StandardCharsets.UTF_8);
                    targetSocket.getOutputStream().write(ok);
                    targetSocket.getOutputStream().flush();
                    return received;
                }
            });

            byte[] handshake = readExact(clientPair.clientSide.getInputStream(), "mysql-target-handshake".length());
            assertThat(new String(handshake, StandardCharsets.UTF_8)).isEqualTo("mysql-target-handshake");

            byte[] comStmtPreparePacket = new byte[]{
                    0x05, 0x00, 0x00, 0x00,
                    0x16,
                    0x00, 0x01, 0x02, 0x03
            };
            clientPair.clientSide.getOutputStream().write(comStmtPreparePacket);
            clientPair.clientSide.getOutputStream().flush();

            assertThat(backendObservedClientBytes.get(2, TimeUnit.SECONDS))
                    .isEqualTo(comStmtPreparePacket);
            byte[] ok = readExact(clientPair.clientSide.getInputStream(), "mysql-target-ok".length());
            assertThat(new String(ok, StandardCharsets.UTF_8)).isEqualTo("mysql-target-ok");

            clientPair.clientSide.close();
            adapterFuture.get(2, TimeUnit.SECONDS);
            executorService.shutdownNow();
        }
    }

    @Test
    void handleClientConnectionObservesSqlAfterTargetAuthenticationOk() throws Exception {
        try (ServerSocket targetServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocketPair clientPair = SocketPair.open()) {
            ExecutorService executorService = Executors.newFixedThreadPool(2);
            MySqlProtocolAdapter adapter = new MySqlProtocolAdapter();
            adapter.setTargetHost(InetAddress.getLoopbackAddress().getHostAddress());
            adapter.setTargetPort(targetServer.getLocalPort());
            List<DatabaseTrafficEvent> observedEvents = new CopyOnWriteArrayList<>();
            adapter.setDatabaseTrafficObserver(observedEvents::add);

            Future<?> adapterFuture = executorService.submit(() -> adapter.handleClientConnection(clientPair.serverSide));
            Future<byte[]> backendObservedQuery = executorService.submit(() -> {
                try (Socket targetSocket = targetServer.accept()) {
                    byte[] handshake = mysqlPacket(0,
                            "mysql-target-handshake".getBytes(StandardCharsets.UTF_8));
                    targetSocket.getOutputStream().write(handshake);
                    targetSocket.getOutputStream().flush();

                    readExact(targetSocket.getInputStream(), 36);

                    byte[] ok = mysqlPacket(2, new byte[]{0x00});
                    targetSocket.getOutputStream().write(ok);
                    targetSocket.getOutputStream().flush();

                    return readExact(targetSocket.getInputStream(), mysqlPacketLength("select * from account"));
                }
            });

            byte[] handshake = mysqlPacket(0,
                    "mysql-target-handshake".getBytes(StandardCharsets.UTF_8));
            readExact(clientPair.clientSide.getInputStream(), handshake.length);
            clientPair.clientSide.getOutputStream().write(mysqlPacket(1, new byte[32]));
            clientPair.clientSide.getOutputStream().flush();
            readExact(clientPair.clientSide.getInputStream(), 5);

            byte[] queryPacket = mysqlCommandPacket(0, MySQLCommandType.COM_QUERY.getCode(), "select * from account");
            clientPair.clientSide.getOutputStream().write(queryPacket);
            clientPair.clientSide.getOutputStream().flush();

            assertThat(backendObservedQuery.get(2, TimeUnit.SECONDS)).isEqualTo(queryPacket);
            assertEventuallyObservedSql(observedEvents, "select * from account");

            clientPair.clientSide.close();
            adapterFuture.get(2, TimeUnit.SECONDS);
            executorService.shutdownNow();
        }
    }

    @Test
    void returnsMySqlErrorPacketWhenTargetIsUnreachable() throws Exception {
        int unreachablePort;
        try (ServerSocket reserved = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            unreachablePort = reserved.getLocalPort();
        }

        try (SocketPair clientPair = SocketPair.open()) {
            MySqlProtocolAdapter adapter = new MySqlProtocolAdapter();
            adapter.setTargetHost(InetAddress.getLoopbackAddress().getHostAddress());
            adapter.setTargetPort(unreachablePort);

            adapter.handleClientConnection(clientPair.serverSide);

            byte[] received = clientPair.clientSide.getInputStream().readAllBytes();

            assertThat(received.length).isGreaterThan(9);
            assertThat(received[0] & 0xFF).isEqualTo((received.length - 4) & 0xFF);
            assertThat(received[1] & 0xFF).isZero();
            assertThat(received[2] & 0xFF).isZero();
            assertThat(received[3] & 0xFF).isZero();
            assertThat(received[4] & 0xFF).isEqualTo(0xFF);
            assertThat(new String(received, 7, 6, StandardCharsets.US_ASCII)).isEqualTo("#08S01");
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

    private static byte[] mysqlCommandPacket(int sequenceId, int command, String sql) {
        byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[sqlBytes.length + 1];
        payload[0] = (byte) command;
        System.arraycopy(sqlBytes, 0, payload, 1, sqlBytes.length);
        return mysqlPacket(sequenceId, payload);
    }

    private static byte[] mysqlPacket(int sequenceId, byte[] payload) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(payload.length & 0xFF);
        outputStream.write((payload.length >> 8) & 0xFF);
        outputStream.write((payload.length >> 16) & 0xFF);
        outputStream.write(sequenceId & 0xFF);
        outputStream.writeBytes(payload);
        return outputStream.toByteArray();
    }

    private static int mysqlPacketLength(String sql) {
        return sql.getBytes(StandardCharsets.UTF_8).length + 5;
    }

    private static void assertEventuallyObservedSql(List<DatabaseTrafficEvent> observedEvents, String sql) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (observedEvents.stream().anyMatch(event -> sql.equals(event.getStatement()))) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(observedEvents)
                .extracting(DatabaseTrafficEvent::getStatement)
                .contains(sql);
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
