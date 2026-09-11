package com.whosly.gateway.integration;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.ProtocolSession;
import org.junit.jupiter.api.Assumptions;

import java.net.ServerSocket;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

abstract class DatabaseGatewayIntegrationTestSupport {

    protected static final IntegrationTestConfig CONFIG = IntegrationTestConfig.load();

    protected final List<DatabaseTrafficEvent> observedEvents = new CopyOnWriteArrayList<>();

    protected void requireIntegrationEnabled() {
        Assumptions.assumeTrue(CONFIG.isEnabled(),
                "Integration tests are disabled. Enable them in integration-test-local.properties.");
    }

    protected int freePort() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }

    protected void stopQuietly(ProtocolAdapter adapter) {
        if (adapter != null && adapter.isRunning()) {
            adapter.stop();
        }
    }

    /**
     * Waits for the adapter to report at least one active session.
     *
     * <p>The session is registered on the adapter's connection thread, so the
     * client may observe a freshly established connection slightly earlier.</p>
     */
    protected ProtocolSession awaitActiveSession(ProtocolAdapter adapter) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Collection<ProtocolSession> sessions = adapter.getActiveSessions();
            if (!sessions.isEmpty()) {
                return sessions.iterator().next();
            }
            Thread.sleep(20);
        }
        assertThat(adapter.getActiveSessions()).isNotEmpty();
        return adapter.getActiveSessions().iterator().next();
    }

    protected void assertObservedSql(String sql) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (observedEvents.stream().anyMatch(event -> sql.equals(event.getStatement()))) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(observedEvents)
                .extracting(DatabaseTrafficEvent::getStatement)
                .contains(sql);
    }
}
