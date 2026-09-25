package com.whosly.gateway.runtime.spi;

import com.whosly.gateway.masking.MaskingEngine;

/**
 * SPI: per-instance masking engine build + rule cascade for runtime lifecycle.
 * Implemented by console {@code InstanceMaskingEngineFactory}.
 */
public interface InstanceMaskingSupport {

    MaskingEngine buildFor(String instanceId);

    /** Cascade-delete H2 masking rules when a console instance is removed. */
    int deleteRulesByInstanceId(String instanceId);

    /** Copy rules from one instance to another (clone). */
    void copyRules(String sourceInstanceId, String targetInstanceId);
}
