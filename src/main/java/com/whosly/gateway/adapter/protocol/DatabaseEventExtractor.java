package com.whosly.gateway.adapter.protocol;

import java.util.List;

/**
 * Extracts database traffic events from protocol-specific cleartext bytes.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-03
 */
@FunctionalInterface
public interface DatabaseEventExtractor {

    List<DatabaseTrafficEvent> extract(TrafficDirection direction, byte[] bytes, int offset, int length);
}
