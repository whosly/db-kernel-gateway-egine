package com.whosly.gateway.runtime.spi;

import java.util.List;
import java.util.Optional;

/**
 * SPI: persisted console-managed instances (typically H2).
 * Implemented by console {@code ConsoleInstanceStore}.
 */
public interface PersistedInstanceStore {

    List<PersistedInstance> findAll();

    Optional<PersistedInstance> findById(String id);

    void upsert(PersistedInstance row);

    boolean deleteById(String id);
}
