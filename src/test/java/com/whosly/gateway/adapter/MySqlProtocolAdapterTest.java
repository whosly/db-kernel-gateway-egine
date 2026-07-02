package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.mysql.MySQLHandshake;
import com.whosly.gateway.adapter.mysql.MySQLPacket;
import com.whosly.gateway.adapter.mysql.MySQLResultSet;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

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
    void testMySQLPacketCreation() {
        byte[] payload = "test payload".getBytes();
        byte[] packet = MySQLPacket.createPacket(payload, 1);
        
        // Check packet length (payload + 4 bytes header)
        assertThat(packet.length).isEqualTo(payload.length + 4);
        
        // Check header fields
        assertThat(packet[0] & 0xFF).isEqualTo(payload.length & 0xFF);
        assertThat(packet[1] & 0xFF).isEqualTo((payload.length >> 8) & 0xFF);
        assertThat(packet[2] & 0xFF).isEqualTo((payload.length >> 16) & 0xFF);
        assertThat(packet[3] & 0xFF).isEqualTo(1); // sequence ID
    }
    
    @Test
    void testHandshakePacketCreation() {
        // 创建一个模拟的数据库连接用于测试
        Connection mockConnection = mock(Connection.class);
        byte[] handshakeData = MySQLHandshake.createHandshakePacket(mockConnection);
        assertThat(handshakeData).isNotNull();
        assertThat(handshakeData.length).isGreaterThan(0);
    }
    
    @Test
    void testOkPacketCreation() {
        byte[] okPacket = MySQLHandshake.createOkPacket(1);
        assertThat(okPacket).isNotNull();
        assertThat(okPacket.length).isGreaterThan(4); // At least header + payload
    }
    
    @Test
    void testErrorPacketCreation() {
        byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Test error", 1);
        assertThat(errorPacket).isNotNull();
        assertThat(errorPacket.length).isGreaterThan(4); // At least header + payload
    }
    
    @Test
    void testEofPacketCreation() {
        byte[] eofPacket = MySQLResultSet.createEofPacket(1);
        assertThat(eofPacket).isNotNull();
        assertThat(eofPacket.length).isGreaterThan(4); // At least header + payload
    }

    @Test
    void handleClientConnectionTransparentlyRelaysTargetAndClientBytes() throws Exception {
        try (ServerSocket targetServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocketPair clientPair = SocketPair.open();
             ExecutorService executorService = Executors.newFixedThreadPool(2)) {
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
