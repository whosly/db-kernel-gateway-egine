package com.whosly.gateway.controller.console;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.console.GatewayInstanceRegistry;
import com.whosly.gateway.console.observe.AlertEvaluationService;
import com.whosly.gateway.console.observe.MetricsHistorySampler;
import com.whosly.gateway.console.persist.AlertThresholdStore;
import com.whosly.gateway.console.persist.ConsoleAuditStore;
import com.whosly.gateway.console.security.ConsoleAuditService;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import com.whosly.gateway.runtime.GatewayListenerRuntime.ManagedListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsoleAlertApiTest {

    private ConsoleAlertApiController api;
    private MetricsHistorySampler sampler;
    private GatewayRuntimeMetrics metrics;

    @BeforeEach
    void setUp() {
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
        sampler = new MetricsHistorySampler(registry, runtime, 5, 20);

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:alert-api-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        AlertThresholdStore store = new AlertThresholdStore(jdbc);
        ConsoleAuditService audit = new ConsoleAuditService(new ConsoleAuditStore(jdbc));
        @SuppressWarnings("unchecked")
        ObjectProvider<ConsoleAuditService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(audit);
        AlertEvaluationService service = new AlertEvaluationService(store, sampler, provider);
        sampler.addAfterSampleListener(service::evaluateSafely);
        api = new ConsoleAlertApiController(service);
    }

    @Test
    void crudAndActiveAlias() {
        ResponseEntity<Map<String, Object>> created = api.createThreshold(
                new ConsoleAlertApiController.AlertThresholdBody(
                        "后端 failover", "backend_fail", "GT", 0d, null, null, true, "CRITICAL"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        String id = String.valueOf(created.getBody().get("id"));

        Map<String, Object> list = api.listThresholds();
        assertThat(list.get("total")).isEqualTo(1);
        assertThat(api.getThreshold(id).get("severity")).isEqualTo("CRITICAL");

        api.updateThreshold(id, new ConsoleAlertApiController.AlertThresholdBody(
                "后端 failover", "backend_fail", "GTE", 1d, 60, "gw-1", true, "WARN"));
        assertThat(api.getThreshold(id).get("instanceId")).isEqualTo("gw-1");

        sampler.sampleNow(); // baseline
        metrics.recordBackendFailover();
        sampler.sampleNow();
        Map<String, Object> active = api.activeAlerts(true);
        assertThat(active.get("total")).isEqualTo(1);
        assertThat(api.activeAlerts(true).get("note").toString()).contains("非 Prometheus");

        Map<String, Object> alias = api.activeAlerts(false);
        assertThat(alias.get("total")).isEqualTo(1);

        assertThat(api.deleteThreshold(id).get("ok")).isEqualTo(true);
        assertThatThrownBy(() -> api.getThreshold(id)).isInstanceOf(IllegalArgumentException.class);
    }
}
