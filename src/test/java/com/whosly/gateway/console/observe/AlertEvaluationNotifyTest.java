package com.whosly.gateway.console.observe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.console.GatewayInstanceRegistry;
import com.whosly.gateway.console.persist.AlertChannelStore;
import com.whosly.gateway.console.persist.AlertThresholdStore;
import com.whosly.gateway.console.persist.ConsoleAuditStore;
import com.whosly.gateway.console.security.ConsoleAuditService;
import com.whosly.gateway.console.security.ConsoleSecretCipher;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import com.whosly.gateway.runtime.GatewayListenerRuntime.ManagedListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlertEvaluationNotifyTest {

    private AlertEvaluationService eval;
    private MetricsHistorySampler sampler;
    private GatewayRuntimeMetrics metrics;
    private HttpServer httpServer;
    private final CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(adapter.isRunning()).thenReturn(true);
        when(adapter.getProtocolName()).thenReturn("MySQL");
        when(adapter.getActiveSessions()).thenReturn(List.of());

        metrics = new GatewayRuntimeMetrics();
        Map<String, ManagedListener> listeners = new LinkedHashMap<>();
        listeners.put("gw-1", new ManagedListener(
                "gw-1", "一", "mysql", "0.0.0.0", 33307, true, true,
                true, "127.0.0.1", 3306, "mysql", "root",
                adapter, metrics, "config"));
        GatewayListenerRuntime runtime = new GatewayListenerRuntime(listeners, "gw-1");
        GatewayInstanceRegistry registry = new GatewayInstanceRegistry(runtime);
        sampler = new MetricsHistorySampler(registry, runtime, 5, 40);

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:alert-eval-notify-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        AlertThresholdStore thrStore = new AlertThresholdStore(jdbc);
        AlertChannelStore chStore = new AlertChannelStore(jdbc);
        ConsoleAuditService audit = new ConsoleAuditService(new ConsoleAuditStore(jdbc));
        @SuppressWarnings("unchecked")
        ObjectProvider<ConsoleAuditService> auditProvider = mock(ObjectProvider.class);
        when(auditProvider.getIfAvailable()).thenReturn(audit);

        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/hook", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        httpServer.start();

        AlertNotificationService notify = new AlertNotificationService(
                chStore, ConsoleSecretCipher.fromBase64MasterKey(null), new ObjectMapper(), auditProvider);
        @SuppressWarnings("unchecked")
        ObjectProvider<AlertNotificationService> notifyProvider = mock(ObjectProvider.class);
        when(notifyProvider.getIfAvailable()).thenReturn(notify);

        eval = new AlertEvaluationService(thrStore, sampler, auditProvider, notifyProvider);
        sampler.addAfterSampleListener(eval::evaluateSafely);

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("url", "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/hook");
        notify.create(new AlertNotificationService.ChannelInput(
                "hook", "WEBHOOK", true, "INFO", 300, cfg));
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void risingEdgeFiresWebhookOnce() {
        eval.create(new AlertEvaluationService.ThresholdInput(
                "拒绝", "deny_count", "GTE", 1d, null, null, true, "WARN"));
        sampler.sampleNow();
        assertThat(bodies).isEmpty();

        metrics.recordPolicyDenial();
        sampler.sampleNow();
        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0)).contains("alert.fired");

        // still firing → cooldown suppresses
        sampler.sampleNow();
        assertThat(bodies).hasSize(1);
    }
}
