package com.whosly.gateway.runtime.spi;

import java.util.Optional;

/**
 * SPI: which database types are creatable at runtime.
 * Implemented by console {@code SupportedDatabaseCatalog}.
 */
public interface InstanceCatalog {

    Optional<DatabaseTypeInfo> lookup(String dbType);
}
