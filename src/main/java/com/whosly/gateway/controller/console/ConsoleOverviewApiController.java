package com.whosly.gateway.controller.console;

import static com.whosly.gateway.controller.console.ConsoleApiModels.*;

import com.whosly.gateway.console.GatewayInstance;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Overview, catalog, health, config summary/export.
 * Base path /console/api — paths and JSON shapes unchanged.
 * Delegates to {@link ConsoleApiController}.
 */
@RestController
@RequestMapping("/console/api")
public class ConsoleOverviewApiController {

    private final ConsoleApiController api;

    public ConsoleOverviewApiController(ConsoleApiController api) {
        this.api = api;
    }

    @GetMapping("/supported-databases")
    public Map<String, Object> supportedDatabases() {
        return api.supportedDatabases();
    }
    @GetMapping("/health")
    public Map<String, Object> health() {
        return api.health();
    }
    @GetMapping("/config/summary")
    public Map<String, Object> configSummary() {
        return api.configSummary();
    }
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return api.overview();
    }
    @GetMapping("/config/export")
    public Map<String, Object> exportConfig() {
        return api.exportConfig();
    }
}
