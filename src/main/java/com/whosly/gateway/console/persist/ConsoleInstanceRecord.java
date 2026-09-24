package com.whosly.gateway.console.persist;

import java.time.Instant;

/**
 * Row in control-plane table {@code gateway_instance} (H2).
 * Password is stored for lab MVP only — never returned by REST; encrypt in a later phase.
 */
public record ConsoleInstanceRecord(
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
