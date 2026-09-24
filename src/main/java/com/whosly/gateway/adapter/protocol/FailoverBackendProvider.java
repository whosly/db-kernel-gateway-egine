package com.whosly.gateway.adapter.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Tries backend endpoints in order until one accepts the TCP connection. */
public final class FailoverBackendProvider implements BackendProvider {

    private static final Logger log = LoggerFactory.getLogger(FailoverBackendProvider.class);

    private final List<BackendEndpoint> endpoints;
    private final int connectTimeoutMillis;
    private final GatewayRuntimeMetrics metrics;

    public FailoverBackendProvider(List<BackendEndpoint> endpoints, int connectTimeoutMillis) {
        this(endpoints, connectTimeoutMillis, GatewayRuntimeMetrics.noop());
    }

    public FailoverBackendProvider(List<BackendEndpoint> endpoints, int connectTimeoutMillis,
                                   GatewayRuntimeMetrics metrics) {
        Objects.requireNonNull(endpoints, "endpoints must not be null");
        if (endpoints.isEmpty()) throw new IllegalArgumentException("endpoints must not be empty");
        this.endpoints = List.copyOf(endpoints);
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.metrics = metrics != null ? metrics : GatewayRuntimeMetrics.noop();
    }

    @Override
    public Socket acquire() throws IOException {
        List<IOException> failures = new ArrayList<>();
        for (int i = 0; i < endpoints.size(); i++) {
            BackendEndpoint endpoint = endpoints.get(i);
            try {
                Socket socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), connectTimeoutMillis);
                if (i > 0) {
                    metrics.recordBackendFailover();
                    log.info("Connected to failover backend {} after {} failed attempt(s)", endpoint, i);
                }
                return socket;
            } catch (IOException e) {
                failures.add(e);
                log.warn("Backend {} unreachable: {}", endpoint, e.getMessage());
            }
        }
        IOException aggregate = new IOException("All " + endpoints.size() + " backend endpoint(s) unreachable");
        for (IOException failure : failures) aggregate.addSuppressed(failure);
        throw aggregate;
    }

    @Override
    public void release(Socket connection) {
        if (connection == null) return;
        try { connection.close(); } catch (IOException ignored) { }
    }

    public List<BackendEndpoint> endpoints() { return endpoints; }
}
