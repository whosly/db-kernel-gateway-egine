package com.whosly.gateway.console.observe;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.console.GatewayInstanceRegistry;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import com.whosly.gateway.runtime.GatewayListenerRuntime.ManagedListener;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetricsHistorySamplerTest {

    @Test
    void samplesOverviewAndInstanceRings() {
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(adapter.isRunning()).thenReturn(true);
        when(adapter.getProtocolName()).thenReturn("MySQL");
        when(adapter.getActiveSessions()).thenReturn(List.of());

        GatewayRuntimeMetrics metrics = new GatewayRuntimeMetrics();
        metrics.recordConnectionAccepted();
        metrics.recordPolicyDenial();

        Map<String, ManagedListener> listeners = new LinkedHashMap<>();
        listeners.put("gw-1", new ManagedListener(
                "gw-1", "一", "mysql", "0.0.0.0", 33307, true, true,
                true, "127.0.0.1", 3306, "mysql", "root",
                adapter, metrics, "config"));

        GatewayListenerRuntime runtime = new GatewayListenerRuntime(listeners, "gw-1");
        GatewayInstanceRegistry registry = new GatewayInstanceRegistry(runtime);
        MetricsHistorySampler sampler = new MetricsHistorySampler(registry, runtime, 5, 20);
        sampler.sampleNow();
        sampler.sampleNow();

        List<Map<String, Object>> overview = sampler.history(null, 10);
        assertThat(overview).hasSize(2);
        assertThat(overview.get(0)).containsKeys("t", "connectionsAccepted", "policyDenials", "activeConnections");
        assertThat(((Number) overview.get(1).get("connectionsAccepted")).longValue()).isEqualTo(1L);

        List<Map<String, Object>> one = sampler.history("gw-1", 10);
        assertThat(one).hasSize(2);
        assertThat(((Number) one.get(0).get("policyDenials")).longValue()).isEqualTo(1L);
    }
}
