package com.whosly.gateway.adapter.protocol;

/**
 * Action requested by a traffic inspector before bytes are forwarded.
 */
public enum TrafficAction {
    FORWARD,
    CLOSE
}
