package com.whosly.gateway.controller.console;

import static com.whosly.gateway.controller.console.ConsoleApiModels.*;
import static com.whosly.gateway.console.security.ConsolePermission.*;

import com.whosly.gateway.console.GatewayInstance;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Instances CRUD/lifecycle under /console/api/v1. */
@RestController
@RequestMapping("/console/api/v1")
public class ConsoleInstanceApiController {

    private final ConsoleApiController api;

    public ConsoleInstanceApiController(ConsoleApiController api) {
        this.api = api;
    }

    @GetMapping("/instances")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_READ + "')")
    public Map<String, Object> listInstances(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "dbType", required = false) String dbType,
            @RequestParam(value = "q", required = false) String q) {
        return api.listInstances(status, dbType, q);
    }

    @GetMapping("/instances/{id}")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_READ + "')")
    public GatewayInstance getInstance(@PathVariable("id") String id) {
        return api.getInstance(id);
    }

    @GetMapping("/instances/{id}/status")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_READ + "')")
    public Map<String, Object> instanceStatus(@PathVariable("id") String id) {
        return api.instanceStatus(id);
    }

    @GetMapping("/instances/{id}/metrics")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_READ + "')")
    public Map<String, Object> instanceMetrics(@PathVariable("id") String id) {
        return api.instanceMetrics(id);
    }

    @PostMapping("/instances/{id}/actions/start")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_START_STOP + "')")
    public GatewayInstance startInstance(@PathVariable("id") String id) {
        return api.startInstance(id);
    }

    @PostMapping("/instances/{id}/actions/stop")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_START_STOP + "')")
    public GatewayInstance stopInstance(@PathVariable("id") String id) {
        return api.stopInstance(id);
    }

    @PostMapping("/instances")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_WRITE + "')")
    public ResponseEntity<GatewayInstance> createInstance(@RequestBody CreateInstanceBody body) {
        GatewayInstance created = api.createInstance(body);
        return ResponseEntity.created(URI.create("/console/api/v1/instances/" + created.id())).body(created);
    }

    @DeleteMapping("/instances/{id}")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_DELETE + "')")
    public Map<String, Object> deleteInstance(@PathVariable("id") String id) {
        return api.deleteInstance(id);
    }

    @PutMapping("/instances/{id}")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_WRITE + "')")
    public GatewayInstance updateInstance(@PathVariable("id") String id,
                                          @RequestBody UpdateInstanceBody body) {
        return api.updateInstance(id, body);
    }

    @PostMapping("/instances/{id}/actions/clone")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_WRITE + "')")
    public ResponseEntity<GatewayInstance> cloneInstance(
            @PathVariable("id") String id,
            @RequestBody(required = false) CloneInstanceBody body) {
        GatewayInstance cloned = api.cloneInstance(id, body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/console/api/v1/instances/" + cloned.id()))
                .body(cloned);
    }

    @PostMapping("/instances/import")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_WRITE + "')")
    public Map<String, Object> importInstances(@RequestBody ImportInstancesBody body) {
        return api.importInstances(body);
    }

    @PostMapping("/instances/bulk-actions")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_START_STOP + "')")
    public Map<String, Object> bulkInstances(@RequestBody BulkInstancesBody body) {
        return api.bulkInstances(body);
    }

    @GetMapping("/instances/{id}/masking-rules")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_READ + "')")
    public Map<String, Object> listMaskingRules(@PathVariable("id") String id) {
        return api.listMaskingRules(id);
    }

    @PostMapping("/instances/{id}/masking-rules")
    @PreAuthorize("@consoleAuthz.has('" + MASKING_WRITE + "')")
    public Map<String, Object> createMaskingRule(@PathVariable("id") String id,
                                                 @RequestBody MaskingRuleBody body) {
        return api.createMaskingRule(id, body);
    }

    @PutMapping("/instances/{id}/masking-rules/{ruleId}")
    @PreAuthorize("@consoleAuthz.has('" + MASKING_WRITE + "')")
    public Map<String, Object> updateMaskingRule(@PathVariable("id") String id,
                                                 @PathVariable("ruleId") String ruleId,
                                                 @RequestBody MaskingRuleBody body) {
        return api.updateMaskingRule(id, ruleId, body);
    }

    @PutMapping("/instances/{id}/masking-rules")
    @PreAuthorize("@consoleAuthz.has('" + MASKING_WRITE + "')")
    public Map<String, Object> replaceMaskingRules(@PathVariable("id") String id,
                                                   @RequestBody List<MaskingRuleBody> bodies) {
        return api.replaceMaskingRules(id, bodies);
    }

    @DeleteMapping("/instances/{id}/masking-rules/{ruleId}")
    @PreAuthorize("@consoleAuthz.has('" + MASKING_WRITE + "')")
    public Map<String, Object> deleteMaskingRule(@PathVariable("id") String id,
                                                 @PathVariable("ruleId") String ruleId) {
        return api.deleteMaskingRule(id, ruleId);
    }

    @PostMapping("/instances/{id}/actions/reload-masking-rules")
    @PreAuthorize("@consoleAuthz.has('" + MASKING_WRITE + "')")
    public Map<String, Object> reloadMasking(@PathVariable("id") String id) {
        return api.reloadMasking(id);
    }

    @GetMapping("/instances/{id}/sessions")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_READ + "')")
    public Map<String, Object> listSessions(@PathVariable("id") String id) {
        return api.listSessions(id);
    }

    @DeleteMapping("/instances/{id}/sessions/{connectionId}")
    @PreAuthorize("@consoleAuthz.has('" + SESSIONS_KILL + "')")
    public Map<String, Object> killSession(@PathVariable("id") String id,
                                           @PathVariable("connectionId") String connectionId) {
        return api.killSession(id, connectionId);
    }

    @PostMapping("/instances/{id}/actions/health-check")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_START_STOP + "')")
    public Map<String, Object> healthCheck(@PathVariable("id") String id) {
        return api.healthCheck(id);
    }

    @GetMapping("/instances/{id}/actions/health-check")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_READ + "')")
    public Map<String, Object> healthCheckGet(@PathVariable("id") String id) {
        return api.healthCheckGet(id);
    }

    @GetMapping("/instances/export")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_READ + "')")
    public Map<String, Object> exportInstances() {
        List<Map<String, Object>> items = api.exportInstances();
        return ConsoleApiModels.listEnvelope(items);
    }

    @GetMapping("/instances/{id}/recent-statements")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_READ + "')")
    public Map<String, Object> recentStatements(
            @PathVariable("id") String id,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return api.recentStatements(id, limit);
    }
}
