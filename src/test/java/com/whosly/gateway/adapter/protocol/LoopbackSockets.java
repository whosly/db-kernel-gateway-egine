package com.whosly.gateway.adapter.protocol;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * A pair of sockets connected to each other on the loopback interface, standing in
 * for the two peers of a relayed connection.
 *
 * <p>Protocol-neutral on purpose: relay tests for both database protocols need the
 * same two things, and neither the sockets nor the read helper have anything to do
 * with a particular wire format.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class LoopbackSockets {

    private LoopbackSockets() {
    }

    public static Pair open() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Socket client = new Socket(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
            Socket server = serverSocket.accept();
            return new Pair(client, server);
        }
    }

    /** Reads exactly {@code length} bytes or fails the test. */
    public static byte[] readExact(InputStream inputStream, int length) throws Exception {
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

    /** One connected socket on each side. */
    public record Pair(Socket clientSide, Socket serverSide) implements AutoCloseable {

        @Override
        public void close() throws Exception {
            clientSide.close();
            serverSide.close();
        }
    }
}
