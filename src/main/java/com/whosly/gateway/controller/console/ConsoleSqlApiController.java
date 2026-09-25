package com.whosly.gateway.controller.console;

import static com.whosly.gateway.controller.console.ConsoleApiModels.*;

import com.whosly.gateway.console.GatewayInstance;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * SQL execute/cancel, schema, history, snippets.
 * Base path /console/api — paths and JSON shapes unchanged.
 * Delegates to {@link ConsoleApiController}.
 */
@RestController
@RequestMapping("/console/api")
public class ConsoleSqlApiController {

    private final ConsoleApiController api;

    public ConsoleSqlApiController(ConsoleApiController api) {
        this.api = api;
    }

    @PostMapping("/instances/{id}/sql/execute")
    public Map<String, Object> executeSql(@PathVariable("id") String id, @RequestBody SqlExecuteBody body) {
        return api.executeSql(id, body);
    }
    @PostMapping("/instances/{id}/sql/cancel")
    public Map<String, Object> cancelSql(@PathVariable("id") String id, @RequestBody(required = false) SqlCancelBody body) {
        return api.cancelSql(id, body);
    }
    @PostMapping("/sql/executions/{executionId}/cancel")
    public Map<String, Object> cancelSqlExecution(@PathVariable("executionId") String executionId) {
        return api.cancelSqlExecution(executionId);
    }
    @GetMapping("/instances/{id}/schema/catalog")
    public Map<String, Object> schemaCatalog(@PathVariable("id") String id) {
        return api.schemaCatalog(id);
    }
    @GetMapping("/instances/{id}/schema/columns")
    public Map<String, Object> schemaColumns(@PathVariable("id") String id, @RequestParam(value = "table", required = false) String table, @RequestParam(value = "schema", required = false) String schema) {
        return api.schemaColumns(id, table, schema);
    }
    @GetMapping("/sql/history")
    public Map<String, Object> sqlHistory(@RequestParam(value = "instanceId", required = false) String instanceId, @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return api.sqlHistory(instanceId, limit);
    }
    @DeleteMapping("/sql/history")
    public Map<String, Object> clearSqlHistory(@RequestParam(value = "id", required = false) String id) {
        return api.clearSqlHistory(id);
    }
    @GetMapping("/sql/snippets")
    public Map<String, Object> listSnippets() {
        return api.listSnippets();
    }
    @PostMapping("/sql/snippets")
    public Map<String, Object> createSnippet(@RequestBody SqlSnippetBody body) {
        return api.createSnippet(body);
    }
    @PutMapping("/sql/snippets/{id}")
    public Map<String, Object> updateSnippet(@PathVariable("id") String id, @RequestBody SqlSnippetBody body) {
        return api.updateSnippet(id, body);
    }
    @DeleteMapping("/sql/snippets/{id}")
    public Map<String, Object> deleteSnippet(@PathVariable("id") String id) {
        return api.deleteSnippet(id);
    }
}
