package com.whosly.gateway.adapter.protocol;

import java.util.List;

/**
 * Extracts SQL events from protocol-specific cleartext client traffic.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@FunctionalInterface
public interface SqlEventExtractor {

    List<SqlTrafficEvent> extract(TrafficDirection direction, byte[] bytes, int offset, int length);
}
