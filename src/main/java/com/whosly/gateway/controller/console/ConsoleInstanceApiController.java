package com.whosly.gateway.controller.console;

import static com.whosly.gateway.controller.console.ConsoleApiModels.*;

import com.whosly.gateway.console.GatewayInstance;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Instances CRUD/lifecycle, masking, sessions, health-check, export, recent statements.
 * Base path /console/api — paths and JSON shapes unchanged.
 * Delegates to {@link ConsoleApiController}.
 */
@RestController
@RequestMapping("/console/api")
public class ConsoleInstanceApiController {

    private final ConsoleApiController api;

    public ConsoleInstanceApiController(ConsoleApiController api) {
        this.api = api;
    }

    @GetMapping("/instances")
    public Map<String, Object> listInstances(@RequestParam(value = "status", required = false) String status, @RequestParam(value = "dbType", required = false) String dbType, @RequestParam(value = "q", required = false) String q) {
        return api.listInstances(status, dbType, q);
    }
    @GetMapping("/instances/{id}")
    public GatewayInstance getInstance(@PathVariable("id") String id) {
        return api.getInstance(id);
    }
    @GetMapping("/instances/{id}/status")
    public Map<String, Object> instanceStatus(@PathVariable("id") String id) {
        return api.instanceStatus(id);
    }
    @GetMapping("/instances/{id}/metrics")
    public Map<String, Object> instanceMetrics(@PathVariable("id") String id) {
        return api.instanceMetrics(id);
    }
    @PostMapping("/instances/{id}/start")
    public Map<String, Object> startInstance(@PathVariable("id") String id) {
        return api.startInstance(id);
    }
    @PostMapping("/instances/{id}/stop")
    public Map<String, Object> stopInstance(@PathVariable("id") String id) {
        return api.stopInstance(id);
    }
    @PostMapping("/instances")
    public GatewayInstance createInstance(@RequestBody CreateInstanceBody body) {
        return api.createInstance(body);
    }
    @DeleteMapping("/instances/{id}")
    public Map<String, Object> deleteInstance(@PathVariable("id") String id) {
        return api.deleteInstance(id);
    }
    @PutMapping("/instances/{id}")
    public GatewayInstance updateInstance(@PathVariable("id") String id, @RequestBody UpdateInstanceBody body) {
        return api.updateInstance(id, body);
    }
    @PostMapping("/instances/{id}/clone")
    public GatewayInstance cloneInstance(@PathVariable("id") String id, @RequestBody(required = false) CloneInstanceBody body) {
        return api.cloneInstance(id, body);
    }
    @PostMapping("/instances/import")
    public Map<String, Object> importInstances(@RequestBody ImportInstancesBody body) {
        return api.importInstances(body);
    }
    @PostMapping("/instances/bulk")
    public Map<String, Object> bulkInstances(@RequestBody BulkInstancesBody body) {
        return api.bulkInstances(body);
    }
    @GetMapping("/instances/{id}/masking-rules")
    public Map<String, Object> listMaskingRules(@PathVariable("id") String id) {
        return api.listMaskingRules(id);
    }
    @PostMapping("/instances/{id}/masking-rules")
    public Map<String, Object> createMaskingRule(@PathVariable("id") String id, @RequestBody MaskingRuleBody body) {
        return api.createMaskingRule(id, body);
    }
    @PutMapping("/instances/{id}/masking-rules/{ruleId}")
    public Map<String, Object> updateMaskingRule(@PathVariable("id") String id, @PathVariable("ruleId") String ruleId, @RequestBody MaskingRuleBody body) {
        return api.updateMaskingRule(id, ruleId, body);
    }
    @PutMapping("/instances/{id}/masking-rules")
    public Map<String, Object> replaceMaskingRules(@PathVariable("id") String id, @RequestBody List<MaskingRuleBody> bodies) {
        return api.replaceMaskingRules(id, bodies);
    }
    @DeleteMapping("/instances/{id}/masking-rules/{ruleId}")
    public Map<String, Object> deleteMaskingRule(@PathVariable("id") String id, @PathVariable("ruleId") String ruleId) {
        return api.deleteMaskingRule(id, ruleId);
    }
    @PostMapping("/instances/{id}/masking-rules/reload")
    public Map<String, Object> reloadMasking(@PathVariable("id") String id) {
        return api.reloadMasking(id);
    }
    @GetMapping("/instances/{id}/sessions")
    public Map<String, Object> listSessions(@PathVariable("id") String id) {
        return api.listSessions(id);
    }
    @DeleteMapping("/instances/{id}/sessions/{connectionId}")
    public Map<String, Object> killSession(@PathVariable("id") String id, @PathVariable("connectionId") String connectionId) {
        return api.killSession(id, connectionId);
    }
    @PostMapping("/instances/{id}/health-check")
    public Map<String, Object> healthCheck(@PathVariable("id") String id) {
        return api.healthCheck(id);
    }
    @GetMapping("/instances/{id}/health-check")
    public Map<String, Object> healthCheckGet(@PathVariable("id") String id) {
        return api.healthCheckGet(id);
    }
    @GetMapping("/instances/export")
    public List<Map<String, Object>> exportInstances() {
        return api.exportInstances();
    }
    @GetMapping("/instances/{id}/recent-statements")
    public Map<String, Object> recentStatements(@PathVariable("id") String id, @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return api.recentStatements(id, limit);
    }
}
