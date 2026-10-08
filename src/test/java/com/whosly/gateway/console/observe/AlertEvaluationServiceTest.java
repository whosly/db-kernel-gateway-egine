package com.whosly.gateway.console.observe;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.console.GatewayInstanceRegistry;
import com.whosly.gateway.console.persist.AlertThresholdStore;
import com.whosly.gateway.console.persist.ConsoleAuditStore;
import com.whosly.gateway.console.security.ConsoleAuditService;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import com.whosly.gateway.runtime.GatewayListenerRuntime.ManagedListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlertEvaluationServiceTest {

    private AlertEvaluationService service;
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
        sampler = new MetricsHistorySampler(registry, runtime, 5, 40);

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:alert-eval-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        AlertThresholdStore store = new AlertThresholdStore(jdbc);
        ConsoleAuditService audit = new ConsoleAuditService(new ConsoleAuditStore(jdbc));
        @SuppressWarnings("unchecked")
        ObjectProvider<ConsoleAuditService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(audit);

        service = new AlertEvaluationService(store, sampler, provider);
        // skip PostConstruct bind — call evaluate manually / sampleNow triggers after we bind
        sampler.addAfterSampleListener(service::evaluateSafely);
    }

    @Test
    void createAndFireOnDenyCount() {
        Map<String, Object> thr = service.create(new AlertEvaluationService.ThresholdInput(
                "拒绝偏高", "deny_count", "GTE", 1d, null, null, true, "WARN"));
        assertThat(thr.get("id")).isNotNull();
        assertThat(thr.get("metricKey")).isEqualTo("deny_count");

        sampler.sampleNow();
        Map<String, Object> active0 = service.listActive(false);
        assertThat((Integer) active0.get("total")).isEqualTo(0);

        metrics.recordPolicyDenial();
        metrics.recordPolicyDenial();
        sampler.sampleNow();

        Map<String, Object> active = service.listActive(true);
        assertThat((Integer) active.get("total")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) active.get("items");
        assertThat(items.get(0).get("metricKey")).isEqualTo("deny_count");
        assertThat(((Number) items.get(0).get("value")).doubleValue()).isEqualTo(2d);
    }

    @Test
    void windowDeltaForCounters() {
        service.create(new AlertEvaluationService.ThresholdInput(
                "窗口增量", "deny_count", "GT", 0d, 30, null, true, "INFO"));
        sampler.sampleNow();
        metrics.recordPolicyDenial();
        sampler.sampleNow();
        Map<String, Object> active = service.listActive(true);
        assertThat((Integer) active.get("total")).isEqualTo(1);
    }

    @Test
    void rejectsBadMetric() {
        assertThatThrownBy(() -> service.create(new AlertEvaluationService.ThresholdInput(
                "x", "not_a_metric", "GT", 1d, null, null, true, "WARN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metricKey");
    }

    @Test
    void normalizeAliases() {
        assertThat(AlertEvaluationService.normalizeMetricKey("policyDenials")).isEqualTo("deny_count");
        assertThat(AlertEvaluationService.normalizeMetricKey("backendFailovers")).isEqualTo("backend_fail");
        assertThat(AlertEvaluationService.normalizeMetricKey("activeConnections")).isEqualTo("active_sessions");
        assertThat(AlertEvaluationService.compare("GT", 2, 1)).isTrue();
        assertThat(AlertEvaluationService.compare("LTE", 1, 1)).isTrue();
    }

    @Test
    void extractErrorRate() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("policyDenials", 1L);
        p.put("connectionsRejectedLimit", 2L);
        p.put("connectionsRejectedPolicy", 3L);
        p.put("opaqueTunnelsDenied", 4L);
        assertThat(AlertEvaluationService.extractValue("error_rate", p)).isEqualTo(10d);
    }
}
