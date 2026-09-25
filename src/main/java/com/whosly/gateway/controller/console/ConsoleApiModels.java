package com.whosly.gateway.controller.console;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared request-body records and list envelopes for /console/api/v1. */
public final class ConsoleApiModels {
    private ConsoleApiModels() {}

    /** Standard list envelope: items + total (+ optional sibling facets). */
    public static Map<String, Object> listEnvelope(List<?> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items != null ? items : List.of());
        body.put("total", items != null ? items.size() : 0);
        return body;
    }

    public static Map<String, Object> listEnvelope(List<?> items, Map<String, ?> facets) {
        Map<String, Object> body = listEnvelope(items);
        if (facets != null) {
            facets.forEach((k, v) -> {
                if (v != null) body.put(k, v);
            });
        }
        return body;
    }


    public record CreateInstanceBody(
            String id,
            String name,
            String dbType,
            String listenHost,
            Integer listenPort,
            String targetHost,
            Integer targetPort,
            String targetDatabase,
            String targetUsername,
            String targetPassword,
            Boolean enabled
    ) {
    }

    public record MaskingRuleBody(
            String id,
            String name,
            String strategy,
            Integer priority,
            String columnName,
            String tableName,
            String namePattern,
            String fixedValue,
            Integer keepPrefix,
            Integer keepSuffix,
            Integer hashHexLength,
            Boolean enabled
    ) {
    }

    public record MaskingKeyBody(String keyId, String keyBase64) {
    }

    public record RiskPolicyBody(
            Boolean enabled,
            List<String> deniedOperations,
            List<String> deniedStatementKeywords
    ) {
    }

    public record UpdateInstanceBody(
            String name,
            String listenHost,
            Integer listenPort,
            String targetHost,
            Integer targetPort,
            String targetDatabase,
            String targetUsername,
            String targetPassword,
            Boolean enabled
    ) {
    }

    public record CloneInstanceBody(
            String id,
            String name,
            Integer listenPort,
            Boolean copyMaskingRules
    ) {
    }

    public record ImportInstancesBody(
            List<Map<String, Object>> instances,
            Boolean replace,
            Boolean skipExisting
    ) {
    }

    public record BulkInstancesBody(
            String action,
            List<String> ids
    ) {
    }

    public record SqlExecuteBody(
            String sql,
            Integer maxRows,
            Integer timeoutMs,
            String executionId,
            Boolean continueOnError
    ) {
    }

    public record SqlCancelBody(
            String executionId
    ) {
    }

    public record SqlSnippetBody(
            String name,
            String sql
    ) {
    }
}
