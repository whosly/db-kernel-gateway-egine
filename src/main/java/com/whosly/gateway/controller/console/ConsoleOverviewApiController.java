package com.whosly.gateway.controller.console;

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
    public Map<String, Object> databases() {
        return api.supportedDatabases();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return api.health();
    }

    @GetMapping("/config")
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
