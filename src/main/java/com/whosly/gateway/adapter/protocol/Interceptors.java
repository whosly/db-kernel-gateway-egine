package com.whosly.gateway.adapter.protocol;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Factories for the interceptor shapes the data path needs.
 *
 * <p>An interceptor must declare its phase, so it cannot be written as a bare
 * lambda. That is deliberate: the phase decides whether a failure is fail-open or
 * fail-closed, and whether the interceptor may rewrite the payload. These
 * factories keep the declaration mandatory while removing the boilerplate, and
 * the phase is chosen at the call site so it cannot be reordered by accident.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class Interceptors {

    private Interceptors() {
    }

    /** Read-only recorder; runs first and never changes the message. */
    public static MessageInterceptor observe(Consumer<WireMessage> observer) {
        Objects.requireNonNull(observer, "observer must not be null");
        return of(InterceptorPhase.OBSERVE, message -> {
            observer.accept(message);
            return TrafficDecision.forward(message);
        });
    }

    /** Enforcement; may deny or close based on the original message. */
    public static MessageInterceptor policy(Function<WireMessage, TrafficDecision> handler) {
        return of(InterceptorPhase.POLICY, handler);
    }

    /** Transformation; runs after policy enforcement by design. */
    public static MessageInterceptor rewrite(UnaryOperator<WireMessage> rewrite) {
        Objects.requireNonNull(rewrite, "rewrite must not be null");
        return of(InterceptorPhase.REWRITE, message -> TrafficDecision.forward(rewrite.apply(message)));
    }

    /** Final recorder; sees the payload that will actually be forwarded. */
    public static MessageInterceptor audit(Consumer<WireMessage> audit) {
        Objects.requireNonNull(audit, "audit must not be null");
        return of(InterceptorPhase.AUDIT, message -> {
            audit.accept(message);
            return TrafficDecision.forward(message);
        });
    }

    /** Builds an interceptor from an explicit phase and handler. */
    public static MessageInterceptor of(InterceptorPhase phase,
                                        Function<WireMessage, TrafficDecision> handler) {
        Objects.requireNonNull(phase, "phase must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        return new MessageInterceptor() {
            @Override
            public InterceptorPhase phase() {
                return phase;
            }

            @Override
            public TrafficDecision intercept(WireMessage message) {
                return handler.apply(message);
            }
        };
    }
}
