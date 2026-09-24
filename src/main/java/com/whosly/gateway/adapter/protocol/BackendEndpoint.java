package com.whosly.gateway.adapter.protocol;

import java.util.Objects;

/** One backend database endpoint (host + port). */
public final class BackendEndpoint {

    private final String host;
    private final int port;

    public BackendEndpoint(String host, int port) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        this.host = host.trim();
        this.port = port;
    }

    public String host() { return host; }
    public int port() { return port; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BackendEndpoint that)) return false;
        return port == that.port && host.equals(that.host);
    }

    @Override
    public int hashCode() { return Objects.hash(host, port); }

    @Override
    public String toString() { return host + ":" + port; }
}
