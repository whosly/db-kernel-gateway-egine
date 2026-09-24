package com.whosly.gateway.controller;

import com.whosly.gateway.adapter.AbstractProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal HTTP ops surface for gateway status and runtime counters (P2-3 / P2-4).
 *
 * <p>Also available via Actuator: {@code GET /actuator/gateway}.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@RestController
@RequestMapping("/gateway")
public class GatewayController {

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    private final ProtocolAdapter protocolAdapter;
    private final GatewayRuntimeMetrics runtimeMetrics;

    @Autowired
    public GatewayController(ProtocolAdapter protocolAdapter, GatewayRuntimeMetrics runtimeMetrics) {
        this.protocolAdapter = protocolAdapter;
        this.runtimeMetrics = runtimeMetrics != null ? runtimeMetrics : GatewayRuntimeMetrics.noop();
    }

    @PostMapping("/start")
    public Map<String, Object> startGateway() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            if (!protocolAdapter.isRunning()) {
                protocolAdapter.start();
                body.put("message", "Gateway started successfully on port " + protocolAdapter.getDefaultPort());
            } else {
                body.put("message", "Gateway is already running");
            }
            body.put("running", protocolAdapter.isRunning());
            body.put("port", protocolAdapter.getDefaultPort());
            return body;
        } catch (Exception e) {
            log.error("Error starting gateway", e);
            body.put("message", "Error starting gateway: " + e.getMessage());
            body.put("running", protocolAdapter.isRunning());
            return body;
        }
    }

    @PostMapping("/stop")
    public Map<String, Object> stopGateway() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            if (protocolAdapter.isRunning()) {
                protocolAdapter.stop();
                body.put("message", "Gateway stopped successfully");
            } else {
                body.put("message", "Gateway is not running");
            }
            body.put("running", protocolAdapter.isRunning());
            return body;
        } catch (Exception e) {
            log.error("Error stopping gateway", e);
            body.put("message", "Error stopping gateway: " + e.getMessage());
            body.put("running", protocolAdapter.isRunning());
            return body;
        }
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", protocolAdapter.isRunning());
        body.put("protocol", protocolAdapter.getProtocolName());
        body.put("port", protocolAdapter.getDefaultPort());
        body.put("activeSessions", protocolAdapter.getActiveSessions().size());
        if (protocolAdapter instanceof AbstractProtocolAdapter abstractAdapter) {
            body.put("activeConnections", abstractAdapter.getActiveConnectionCount());
            body.put("maxConnections", abstractAdapter.getMaxConnections());
        }
        body.put("message", protocolAdapter.isRunning()
                ? "Gateway is running on port " + protocolAdapter.getDefaultPort()
                : "Gateway is not running");
        return body;
    }

    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", protocolAdapter.isRunning());
        body.put("port", protocolAdapter.getDefaultPort());
        body.put("activeSessions", protocolAdapter.getActiveSessions().size());
        body.putAll(runtimeMetrics.snapshot());
        return body;
    }
}
