package com.whosly.gateway.adapter.protocol;

import java.util.concurrent.atomic.AtomicLong;

/** Lightweight process-local counters for production operations visibility. */
public final class GatewayRuntimeMetrics {

    private static final GatewayRuntimeMetrics NOOP = new GatewayRuntimeMetrics(true);

    private final boolean noop;
    private final AtomicLong connectionsAccepted = new AtomicLong();
    private final AtomicLong connectionsRejectedLimit = new AtomicLong();
    private final AtomicLong connectionsRejectedPolicy = new AtomicLong();
    private final AtomicLong opaqueTunnelsEntered = new AtomicLong();
    private final AtomicLong opaqueTunnelsDenied = new AtomicLong();
    private final AtomicLong backendFailovers = new AtomicLong();
    private final AtomicLong policyDenials = new AtomicLong();

    public GatewayRuntimeMetrics() { this(false); }
    private GatewayRuntimeMetrics(boolean noop) { this.noop = noop; }
    public static GatewayRuntimeMetrics noop() { return NOOP; }

    public void recordConnectionAccepted() { if (!noop) connectionsAccepted.incrementAndGet(); }
    public void recordConnectionRejectedLimit() { if (!noop) connectionsRejectedLimit.incrementAndGet(); }
    public void recordConnectionRejectedPolicy() { if (!noop) connectionsRejectedPolicy.incrementAndGet(); }
    public void recordOpaqueTunnelEntered() { if (!noop) opaqueTunnelsEntered.incrementAndGet(); }
    public void recordOpaqueTunnelDenied() { if (!noop) opaqueTunnelsDenied.incrementAndGet(); }
    public void recordBackendFailover() { if (!noop) backendFailovers.incrementAndGet(); }
    public void recordPolicyDenial() { if (!noop) policyDenials.incrementAndGet(); }

    public long connectionsAccepted() { return connectionsAccepted.get(); }
    public long connectionsRejectedLimit() { return connectionsRejectedLimit.get(); }
    public long connectionsRejectedPolicy() { return connectionsRejectedPolicy.get(); }
    public long opaqueTunnelsEntered() { return opaqueTunnelsEntered.get(); }
    public long opaqueTunnelsDenied() { return opaqueTunnelsDenied.get(); }
    public long backendFailovers() { return backendFailovers.get(); }
    public long policyDenials() { return policyDenials.get(); }
}
