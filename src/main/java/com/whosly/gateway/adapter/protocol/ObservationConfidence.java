package com.whosly.gateway.adapter.protocol;

/**
 * How much the observed session state can be trusted.
 *
 * <p>A transparent proxy observes cleartext traffic on a best-effort basis. When
 * observation is incomplete the gateway must say so instead of publishing a
 * wrong value: auditing, risk control, routing and connection pooling all
 * consume this state (rule 2.10).</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public enum ObservationConfidence {

    /** Observation matches the protocol exactly. */
    CONFIRMED,

    /**
     * An anomaly was seen but observation continues: the last confirmed values
     * are kept and not updated from the uncertain part of the stream.
     */
    UNCERTAIN,

    /**
     * The phase machine cannot recover on its own, so session state writes stop
     * until the protocol resynchronises (for MySQL, the next client command).
     */
    SUSPENDED
}
