package com.whosly.gateway.controller.console;

import static com.whosly.gateway.console.security.ConsolePermission.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Overview / databases / health / config under /console/api/v1. */
@RestController
@RequestMapping("/console/api/v1")
public class ConsoleOverviewApiController {

    private final ConsoleApiController api;

    public ConsoleOverviewApiController(ConsoleApiController api) {
        this.api = api;
    }

    @GetMapping("/databases")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_READ + "')")
    public Map<String, Object> databases() {
        return api.supportedDatabases();
    }

    @GetMapping("/health")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_READ + "')")
    public Map<String, Object> health() {
        return api.health();
    }

    @GetMapping("/config")
    @PreAuthorize("@consoleAuthz.has('" + CONFIG_READ + "')")
    public Map<String, Object> configSummary() {
        return api.configSummary();
    }

    @GetMapping("/overview")
    @PreAuthorize("@consoleAuthz.has('" + INSTANCES_READ + "')")
    public Map<String, Object> overview() {
        return api.overview();
    }

    @GetMapping("/config/export")
    @PreAuthorize("@consoleAuthz.has('" + CONFIG_READ + "')")
    public Map<String, Object> exportConfig() {
        return api.exportConfig();
    }
}
