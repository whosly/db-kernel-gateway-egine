package com.whosly.gateway.adapter.protocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Opens one dedicated target connection per client session.
 *
 * <p>This is the transparent-proxy behaviour: nothing is shared between sessions,
 * so no session state can leak and no reset strategy is needed. Releasing a
 * connection closes it.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class DirectBackendProvider implements BackendProvider {

    private final String host;
    private final int port;
    private final int connectTimeoutMillis;

    public DirectBackendProvider(String host, int port, int connectTimeoutMillis) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    @Override
    public Socket acquire() throws IOException {
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
        return socket;
    }

    @Override
    public void release(Socket connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (IOException ignored) {
            // Already closing.
        }
    }
}
