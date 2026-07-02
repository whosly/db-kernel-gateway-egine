package com.whosly.gateway.adapter.protocol;

import java.util.List;

/**
 * Extracts SQL events from protocol-specific cleartext client traffic.
 */
@FunctionalInterface
public interface SqlEventExtractor {

    List<SqlTrafficEvent> extract(TrafficDirection direction, byte[] bytes, int offset, int length);
}
