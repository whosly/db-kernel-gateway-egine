package com.whosly.gateway.controller.console;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Audit + metrics history under /console/api/v1. */
@RestController
@RequestMapping("/console/api/v1")
public class ConsoleAuditApiController {

    private final ConsoleApiController api;

    public ConsoleAuditApiController(ConsoleApiController api) {
        this.api = api;
    }

    @GetMapping("/audit/operations")
    public Map<String, Object> listAudit(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "action", required = false) String action) {
        return api.listAudit(limit, action);
    }

    @GetMapping("/audit/status")
    public Map<String, Object> auditStatus() {
        return api.auditStatus();
    }

    @GetMapping("/audit/traffic")
    public Map<String, Object> listAuditTraffic(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "before", required = false) Long before,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "protocol", required = false) String protocol,
            @RequestParam(value = "operation", required = false) String operation) {
        return api.listAuditSpool(limit, before, source, protocol, operation);
    }

    @GetMapping("/metrics/history")
    public Map<String, Object> metricsHistory(
            @RequestParam(value = "instanceId", required = false) String instanceId,
            @RequestParam(value = "limit", defaultValue = "120") int limit) {
        return api.metricsHistory(instanceId, limit);
    }
}
