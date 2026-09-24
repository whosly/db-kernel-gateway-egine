package com.whosly.gateway.adapter.protocol;

/**
 * Execution order of interceptors in the data path.
 *
 * <p>The order is deliberate: observation records what the client actually sent,
 * policy is enforced on that same original message, and only then may a rewrite
 * transform the payload. A rewrite therefore can never smuggle a construct past
 * policy enforcement, and audit always sees the final payload.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public enum InterceptorPhase {

    /** Read-only observation: audit events and session state. */
    OBSERVE,

    /** Enforcement: may deny or close the connection, based on the original message. */
    POLICY,

    /** Transformation: may replace the payload. Runs after policy by design. */
    REWRITE,

    /** Final recording of what was forwarded. */
    AUDIT
}
