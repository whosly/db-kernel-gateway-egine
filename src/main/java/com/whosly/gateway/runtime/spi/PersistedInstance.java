package com.whosly.gateway.runtime.spi;

import java.time.Instant;

/**
 * Neutral DTO for a console-managed gateway instance row.
 * Neutral control-plane instance row; console H2 store implements {@link PersistedInstanceStore}.
 */
public record PersistedInstance(
        String id,
        String name,
        String dbType,
        String listenHost,
        int listenPort,
        boolean enabled,
        String targetHost,
        int targetPort,
        String targetDatabase,
        String targetUsername,
        String targetPassword,
        Instant createdAt,
        Instant updatedAt
) {
}
