package com.whosly.gateway.controller;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.adapter.protocol.ProtocolSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayOpsSurfaceTest {

    @Test
    void restMetricsExposesRuntimeCounters() {
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(adapter.isRunning()).thenReturn(true);
        when(adapter.getProtocolName()).thenReturn("MySQL");
        when(adapter.getDefaultPort()).thenReturn(3307);
        when(adapter.getActiveSessions()).thenReturn(List.of());

        GatewayRuntimeMetrics metrics = new GatewayRuntimeMetrics();
        metrics.recordConnectionAccepted();
        metrics.recordPolicyDenial();

        GatewayController controller = new GatewayController(adapter, metrics);
        Map<String, Object> body = controller.getMetrics();

        assertThat(body.get("running")).isEqualTo(true);
        assertThat(body.get("port")).isEqualTo(3307);
        assertThat(body.get("connectionsAccepted")).isEqualTo(1L);
        assertThat(body.get("policyDenials")).isEqualTo(1L);
    }

    @Test
    void restStatusReportsIdleGateway() {
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(adapter.isRunning()).thenReturn(false);
        when(adapter.getProtocolName()).thenReturn("PostgreSQL");
        when(adapter.getDefaultPort()).thenReturn(5433);
        when(adapter.getActiveSessions()).thenReturn(List.<ProtocolSession>of());

        GatewayController controller = new GatewayController(adapter, new GatewayRuntimeMetrics());
        Map<String, Object> body = controller.getStatus();

        assertThat(body.get("running")).isEqualTo(false);
        assertThat(body.get("protocol")).isEqualTo("PostgreSQL");
        assertThat(body.get("message").toString()).contains("not running");
    }

    @Test
    void actuatorEndpointNestsMetricsSnapshot() {
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(adapter.isRunning()).thenReturn(true);
        when(adapter.getProtocolName()).thenReturn("MySQL");
        when(adapter.getDefaultPort()).thenReturn(3307);
        when(adapter.getActiveSessions()).thenReturn(List.of());

        GatewayRuntimeMetrics metrics = new GatewayRuntimeMetrics();
        metrics.recordOpaqueTunnelEntered();
        metrics.recordBackendFailover();

        GatewayActuatorEndpoint endpoint = new GatewayActuatorEndpoint(adapter, metrics);
        Map<String, Object> body = endpoint.gateway();

        assertThat(body.get("running")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Long> nested = (Map<String, Long>) body.get("metrics");
        assertThat(nested.get("opaqueTunnelsEntered")).isEqualTo(1L);
        assertThat(nested.get("backendFailovers")).isEqualTo(1L);
    }
}
