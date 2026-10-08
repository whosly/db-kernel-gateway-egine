package com.whosly.gateway.runtime.spi;

import java.util.Objects;

/**
 * Neutral catalog view consumed by {@link com.whosly.gateway.runtime.GatewayListenerRuntime}.
 * Console {@code SupportedDatabaseInfo} maps into this; runtime must not import console types.
 */
public record DatabaseTypeInfo(String id, boolean creatable) {
    public DatabaseTypeInfo {
        Objects.requireNonNull(id, "id");
    }
}
