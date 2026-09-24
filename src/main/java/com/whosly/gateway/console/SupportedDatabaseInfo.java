package com.whosly.gateway.console;

import java.util.Objects;

/**
 * Public, non-secret view of one supported database catalog entry.
 */
public record SupportedDatabaseInfo(
        String id,
        String displayName,
        boolean enabled,
        String maturity,
        int defaultProxyPort,
        int defaultTargetPort,
        String notes,
        boolean registered,
        boolean creatable,
        boolean consoleCreateAllowed
) {
    public SupportedDatabaseInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(maturity, "maturity");
        notes = notes != null ? notes : "";
    }
}
