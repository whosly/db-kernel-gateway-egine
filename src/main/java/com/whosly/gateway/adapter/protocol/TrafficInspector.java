package com.whosly.gateway.adapter.protocol;

/**
 * Inspects traffic while preserving transparent byte forwarding semantics.
 */
@FunctionalInterface
public interface TrafficInspector {

    TrafficAction onBytes(TrafficDirection direction, byte[] bytes, int offset, int length);

    static TrafficInspector passThrough() {
        return (direction, bytes, offset, length) -> TrafficAction.FORWARD;
    }
}
