package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
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

class PostgreSQLProtocolAdapterTest {

    @Test
    void handleClientConnectionTransparentlyRelaysStartupAndTargetResponses() throws Exception {
        try (ServerSocket targetServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocketPair clientPair = SocketPair.open();
             ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            PostgreSQLProtocolAdapter adapter = new PostgreSQLProtocolAdapter();
            adapter.setTargetHost(InetAddress.getLoopbackAddress().getHostAddress());
            adapter.setTargetPort(targetServer.getLocalPort());

            Future<?> adapterFuture = executorService.submit(() -> adapter.handleClientConnection(clientPair.serverSide));
            Future<byte[]> backendObservedClientBytes = executorService.submit(() -> {
                try (Socket targetSocket = targetServer.accept()) {
                    byte[] startup = readExact(targetSocket.getInputStream(), 8);

                    byte[] response = "postgres-target-auth".getBytes(StandardCharsets.UTF_8);
                    targetSocket.getOutputStream().write(response);
                    targetSocket.getOutputStream().flush();

                    byte[] parse = readExact(targetSocket.getInputStream(), 9);
                    byte[] observed = new byte[startup.length + parse.length];
                    System.arraycopy(startup, 0, observed, 0, startup.length);
                    System.arraycopy(parse, 0, observed, startup.length, parse.length);
                    return observed;
                }
            });

            byte[] startup = new byte[]{0x00, 0x00, 0x00, 0x08, 0x00, 0x03, 0x00, 0x02};
            clientPair.clientSide.getOutputStream().write(startup);
            clientPair.clientSide.getOutputStream().flush();

            byte[] response = readExact(clientPair.clientSide.getInputStream(), "postgres-target-auth".length());
            assertThat(new String(response, StandardCharsets.UTF_8)).isEqualTo("postgres-target-auth");

            byte[] parseMessage = new byte[]{'P', 0x00, 0x00, 0x00, 0x08, 0x00, 'q', 0x00, 0x00};
            clientPair.clientSide.getOutputStream().write(parseMessage);
            clientPair.clientSide.getOutputStream().flush();

            byte[] expected = new byte[startup.length + parseMessage.length];
            System.arraycopy(startup, 0, expected, 0, startup.length);
            System.arraycopy(parseMessage, 0, expected, startup.length, parseMessage.length);
            assertThat(backendObservedClientBytes.get(2, TimeUnit.SECONDS)).isEqualTo(expected);

            clientPair.clientSide.close();
            adapterFuture.get(2, TimeUnit.SECONDS);
            executorService.shutdownNow();
        }
    }

    @Test
    void handleClientConnectionObservesSimpleQuerySql() throws Exception {
        try (ServerSocket targetServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocketPair clientPair = SocketPair.open();
             ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            PostgreSQLProtocolAdapter adapter = new PostgreSQLProtocolAdapter();
            adapter.setTargetHost(InetAddress.getLoopbackAddress().getHostAddress());
            adapter.setTargetPort(targetServer.getLocalPort());
            List<DatabaseTrafficEvent> observedEvents = new CopyOnWriteArrayList<>();
            adapter.setDatabaseTrafficObserver(observedEvents::add);

            Future<?> adapterFuture = executorService.submit(() -> adapter.handleClientConnection(clientPair.serverSide));
            Future<byte[]> backendObservedQuery = executorService.submit(() -> {
                try (Socket targetSocket = targetServer.accept()) {
                    readExact(targetSocket.getInputStream(), 8);

                    byte[] ready = "postgres-target-ready".getBytes(StandardCharsets.UTF_8);
                    targetSocket.getOutputStream().write(ready);
                    targetSocket.getOutputStream().flush();

                    return readExact(targetSocket.getInputStream(), postgreSqlMessageLength("select * from account"));
                }
            });

            byte[] startup = new byte[]{0x00, 0x00, 0x00, 0x08, 0x00, 0x03, 0x00, 0x02};
            clientPair.clientSide.getOutputStream().write(startup);
            clientPair.clientSide.getOutputStream().flush();
            readExact(clientPair.clientSide.getInputStream(), "postgres-target-ready".length());

            byte[] query = postgreSqlQueryMessage("select * from account");
            clientPair.clientSide.getOutputStream().write(query);
            clientPair.clientSide.getOutputStream().flush();

            assertThat(backendObservedQuery.get(2, TimeUnit.SECONDS)).isEqualTo(query);
            assertEventuallyObservedSql(observedEvents, "select * from account");

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

    private static byte[] postgreSqlQueryMessage(String sql) {
        byte[] body = cstring(sql);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + body.length);
        buffer.put((byte) 'Q');
        buffer.putInt(body.length + 4);
        buffer.put(body);
        return buffer.array();
    }

    private static byte[] cstring(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] cstring = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, cstring, 0, bytes.length);
        return cstring;
    }

    private static int postgreSqlMessageLength(String sql) {
        return 1 + 4 + sql.getBytes(StandardCharsets.UTF_8).length + 1;
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
