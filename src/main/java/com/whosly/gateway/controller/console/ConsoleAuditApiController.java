package com.whosly.gateway.controller.console;

import static com.whosly.gateway.controller.console.ConsoleApiModels.*;

import com.whosly.gateway.console.GatewayInstance;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Console ops audit, spool browse, metrics history.
 * Base path /console/api — paths and JSON shapes unchanged.
 * Delegates to {@link ConsoleApiController}.
 */
@RestController
@RequestMapping("/console/api")
public class ConsoleAuditApiController {

    private final ConsoleApiController api;

    public ConsoleAuditApiController(ConsoleApiController api) {
        this.api = api;
    }

    @GetMapping("/audit")
    public Map<String, Object> listAudit(@RequestParam(value = "limit", defaultValue = "50") int limit, @RequestParam(value = "action", required = false) String action) {
        return api.listAudit(limit, action);
    }
    @GetMapping("/audit/status")
    public Map<String, Object> auditStatus() {
        return api.auditStatus();
    }
    @GetMapping("/audit/spool")
    public Map<String, Object> listAuditSpool(@RequestParam(value = "limit", defaultValue = "50") int limit, @RequestParam(value = "before", required = false) Long before, @RequestParam(value = "source", required = false) String source, @RequestParam(value = "protocol", required = false) String protocol, @RequestParam(value = "operation", required = false) String operation) {
        return api.listAuditSpool(limit, before, source, protocol, operation);
    }
    @GetMapping("/audit/records")
    public Map<String, Object> listAuditRecords(@RequestParam(value = "limit", defaultValue = "50") int limit, @RequestParam(value = "before", required = false) Long before, @RequestParam(value = "source", required = false) String source, @RequestParam(value = "protocol", required = false) String protocol, @RequestParam(value = "operation", required = false) String operation) {
        return api.listAuditRecords(limit, before, source, protocol, operation);
    }
    @GetMapping("/metrics/history")
    public Map<String, Object> metricsHistory(@RequestParam(value = "instanceId", required = false) String instanceId, @RequestParam(value = "limit", defaultValue = "120") int limit) {
        return api.metricsHistory(instanceId, limit);
    }
}
