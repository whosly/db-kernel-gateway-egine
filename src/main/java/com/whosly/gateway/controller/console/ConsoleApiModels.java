package com.whosly.gateway.controller.console;

import java.util.List;
import java.util.Map;

/** Shared request-body records for /console/api (JSON shapes unchanged). */
public final class ConsoleApiModels {
    private ConsoleApiModels() {}

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
