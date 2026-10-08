package com.whosly.gateway.runtime.spi;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficObserver;

/**
 * SPI: attach a per-instance recent-traffic observer to a newly built adapter.
 * Implemented by {@link com.whosly.gateway.runtime.observe.RecentTrafficRing}.
 */
public interface TrafficRingAttachment {

    DatabaseTrafficObserver forInstance(String instanceId);
}
