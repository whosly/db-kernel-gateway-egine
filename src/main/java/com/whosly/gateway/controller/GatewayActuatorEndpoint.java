package com.whosly.gateway.controller;

import com.whosly.gateway.adapter.AbstractProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin Actuator endpoint exposing in-process {@link GatewayRuntimeMetrics}
 * plus basic run state. No Micrometer registry required (P2-4).
 *
 * <p>Path: {@code GET /actuator/gateway} (when exposed).</p>
 */
@Component
@Endpoint(id = "gateway")
public class GatewayActuatorEndpoint {

    private final ProtocolAdapter protocolAdapter;
    private final GatewayRuntimeMetrics runtimeMetrics;

    @Autowired
    public GatewayActuatorEndpoint(ProtocolAdapter protocolAdapter, GatewayRuntimeMetrics runtimeMetrics) {
        this.protocolAdapter = protocolAdapter;
        this.runtimeMetrics = runtimeMetrics != null ? runtimeMetrics : GatewayRuntimeMetrics.noop();
    }

    @ReadOperation
    public Map<String, Object> gateway() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", protocolAdapter.isRunning());
        body.put("protocol", protocolAdapter.getProtocolName());
        body.put("port", protocolAdapter.getDefaultPort());
        body.put("activeSessions", protocolAdapter.getActiveSessions().size());
        if (protocolAdapter instanceof AbstractProtocolAdapter abstractAdapter) {
            body.put("activeConnections", abstractAdapter.getActiveConnectionCount());
            body.put("maxConnections", abstractAdapter.getMaxConnections());
        }
        body.put("metrics", runtimeMetrics.snapshot());
        return body;
    }
}
