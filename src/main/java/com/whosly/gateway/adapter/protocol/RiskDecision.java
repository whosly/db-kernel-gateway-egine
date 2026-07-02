package com.whosly.gateway.adapter.protocol;

import java.util.Objects;

/**
 * Result of evaluating a SQL traffic event against risk policy.
 */
public final class RiskDecision {

    private static final RiskDecision ALLOW = new RiskDecision(true, "allowed");

    private final boolean allowed;
    private final String reason;

    private RiskDecision(boolean allowed, String reason) {
        this.allowed = allowed;
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getReason() {
        return reason;
    }

    public static RiskDecision allow() {
        return ALLOW;
    }

    public static RiskDecision deny(String reason) {
        return new RiskDecision(false, reason);
    }
}
