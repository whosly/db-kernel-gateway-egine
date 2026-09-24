package com.whosly.gateway.console.persist;

import java.time.Instant;

/**
 * Row in control-plane table {@code gateway_instance_masking_rule} (H2).
 * Protocol-agnostic: bound to a gateway instance id, not a DB brand.
 */
public record MaskingRuleRecord(
        String id,
        String instanceId,
        String name,
        String strategy,
        int priority,
        String columnName,
        String tableName,
        String namePattern,
        String fixedValue,
        Integer keepPrefix,
        Integer keepSuffix,
        Integer hashHexLength,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
