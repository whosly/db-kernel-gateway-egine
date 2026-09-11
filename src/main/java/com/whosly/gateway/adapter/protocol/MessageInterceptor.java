package com.whosly.gateway.adapter.protocol;

/**
 * One step of the data path.
 *
 * <p>An interceptor inspects one {@link WireMessage} and returns what should
 * happen to it. Returning {@link TrafficDecision#forward(WireMessage)} with the
 * message it received means "no opinion": nothing changes and the next
 * interceptor runs. Returning a decision with a different action stops the
 * chain.</p>
 *
 * <p>Interceptors must never throw their way into the data path: observation and
 * audit problems are fail-open, policy may deny, and a rewrite must fail closed
 * (AGENTS.md transparency invariants).</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public interface MessageInterceptor {

    /** Position in the chain; see {@link InterceptorPhase}. */
    InterceptorPhase phase();

    /** Inspects one message. Must not return {@code null}. */
    TrafficDecision intercept(WireMessage message);
}
