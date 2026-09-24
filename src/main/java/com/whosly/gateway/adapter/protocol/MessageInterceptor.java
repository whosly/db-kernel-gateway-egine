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

    /**
     * Message boundaries this interceptor needs for a direction, or {@code null}
     * when it can work on whatever bytes a read produced.
     *
     * <p>Returning a bounder makes the data path assemble whole messages before
     * calling the chain, which means holding the earlier bytes of a message that
     * has not fully arrived: a deliberate and bounded delay (rule 8.1). Only an
     * interceptor that genuinely cannot act on a partial message should return
     * one; observation and audit never do, so by default every read is forwarded
     * as it arrives (rule 2.10).</p>
     *
     * @param direction direction whose framing is needed
     * @return the bounder, or {@code null} to accept partial messages
     */
    default MessageBounder messageBounder(TrafficDirection direction) {
        return null;
    }
}
