package com.whosly.gateway.adapter.protocol;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class GatewayRuntimeMetricsTest {
    @Test void countsEvents() {
        GatewayRuntimeMetrics m = new GatewayRuntimeMetrics();
        m.recordConnectionAccepted(); m.recordConnectionRejectedLimit();
        m.recordOpaqueTunnelEntered(); m.recordOpaqueTunnelDenied();
        m.recordBackendFailover(); m.recordPolicyDenial();
        assertThat(m.connectionsAccepted()).isEqualTo(1);
        assertThat(m.policyDenials()).isEqualTo(1);
    }
    @Test void noopDoesNotCount() {
        GatewayRuntimeMetrics m = GatewayRuntimeMetrics.noop();
        m.recordConnectionAccepted();
        assertThat(m.connectionsAccepted()).isZero();
    }
}
