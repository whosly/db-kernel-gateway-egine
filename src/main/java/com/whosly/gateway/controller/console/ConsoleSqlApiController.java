package com.whosly.gateway.controller.console;

import static com.whosly.gateway.controller.console.ConsoleApiModels.*;
import static com.whosly.gateway.console.security.ConsolePermission.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** SQL / schema / history / snippets under /console/api/v1. */
@RestController
@RequestMapping("/console/api/v1")
public class ConsoleSqlApiController {

    private final ConsoleApiController api;

    public ConsoleSqlApiController(ConsoleApiController api) {
        this.api = api;
    }

    @PostMapping("/instances/{id}/sql/executions")
    @PreAuthorize("@consoleAuthz.has('" + SQL_EXECUTE + "')")
    public Map<String, Object> executeSql(@PathVariable("id") String id,
                                          @RequestBody SqlExecuteBody body) {
        return api.executeSql(id, body);
    }

    @PostMapping("/instances/{id}/sql/executions/cancel")
    @PreAuthorize("@consoleAuthz.has('" + SQL_EXECUTE + "')")
    public Map<String, Object> cancelSql(@PathVariable("id") String id,
                                         @RequestBody(required = false) SqlCancelBody body) {
        return api.cancelSql(id, body);
    }

    @PostMapping("/sql/executions/{executionId}/cancel")
    @PreAuthorize("@consoleAuthz.has('" + SQL_EXECUTE + "')")
    public Map<String, Object> cancelSqlExecution(@PathVariable("executionId") String executionId) {
        return api.cancelSqlExecution(executionId);
    }

    @GetMapping("/instances/{id}/schema/catalog")
    @PreAuthorize("@consoleAuthz.has('" + SCHEMA_READ + "')")
    public Map<String, Object> schemaCatalog(@PathVariable("id") String id) {
        return api.schemaCatalog(id);
    }

    @GetMapping("/instances/{id}/schema/columns")
    @PreAuthorize("@consoleAuthz.has('" + SCHEMA_READ + "')")
    public Map<String, Object> schemaColumns(
            @PathVariable("id") String id,
            @RequestParam(value = "table", required = false) String table,
            @RequestParam(value = "schema", required = false) String schema) {
        return api.schemaColumns(id, table, schema);
    }

    @GetMapping("/sql/history")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_READ + "')")
    public Map<String, Object> sqlHistory(
            @RequestParam(value = "instanceId", required = false) String instanceId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return api.sqlHistory(instanceId, limit);
    }

    @DeleteMapping("/sql/history")
    @PreAuthorize("@consoleAuthz.has('" + SQL_EXECUTE + "')")
    public Map<String, Object> clearSqlHistory(@RequestParam(value = "id", required = false) String id) {
        return api.clearSqlHistory(id);
    }

    @GetMapping("/sql/snippets")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_READ + "')")
    public Map<String, Object> listSnippets() {
        return api.listSnippets();
    }

    @PostMapping("/sql/snippets")
    @PreAuthorize("@consoleAuthz.has('" + SQL_EXECUTE + "')")
    public Map<String, Object> createSnippet(@RequestBody SqlSnippetBody body) {
        return api.createSnippet(body);
    }

    @PutMapping("/sql/snippets/{id}")
    @PreAuthorize("@consoleAuthz.has('" + SQL_EXECUTE + "')")
    public Map<String, Object> updateSnippet(@PathVariable("id") String id,
                                             @RequestBody SqlSnippetBody body) {
        return api.updateSnippet(id, body);
    }

    @DeleteMapping("/sql/snippets/{id}")
    @PreAuthorize("@consoleAuthz.has('" + SQL_EXECUTE + "')")
    public Map<String, Object> deleteSnippet(@PathVariable("id") String id) {
        return api.deleteSnippet(id);
    }
}
