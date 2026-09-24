package com.whosly.gateway.adapter.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Ordered chain of {@link MessageInterceptor}s driving the data path.
 *
 * <p>The chain is where future capabilities attach: SQL rewriting and result-set
 * masking are new interceptors, not changes to the relay. Today the chain holds
 * the observing/risk interceptor only, so every message leaves the pipeline
 * unmutated and the relay writes the original bytes.</p>
 *
 * <p>Failure handling follows the transparency invariants: observation and audit
 * are fail-open, policy and rewrite are fail-closed.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class MessagePipeline implements TrafficInspector {

    private static final Logger log = LoggerFactory.getLogger(MessagePipeline.class);

    private final List<MessageInterceptor> interceptors;

    public MessagePipeline(List<MessageInterceptor> interceptors) {
        Objects.requireNonNull(interceptors, "interceptors must not be null");
        List<MessageInterceptor> ordered = new ArrayList<>(interceptors);
        // Enum declaration order is the phase order; see InterceptorPhase.
        ordered.sort(Comparator.comparing(MessageInterceptor::phase));
        this.interceptors = List.copyOf(ordered);
    }

    public static MessagePipeline of(MessageInterceptor... interceptors) {
        return new MessagePipeline(List.of(interceptors));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Interceptors in the order they run. */
    public List<MessageInterceptor> interceptors() {
        return interceptors;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reports the first bounder any interceptor declares, in phase order. When
     * none does — the case for every deployment that only observes — the relay
     * keeps forwarding each read immediately and no message is ever held.</p>
     */
    @Override
    public MessageBounder messageBounder(TrafficDirection direction) {
        for (MessageInterceptor interceptor : interceptors) {
            MessageBounder bounder = interceptor.messageBounder(direction);
            if (bounder != null) {
                return bounder;
            }
        }
        return null;
    }

    @Override
    public TrafficDecision inspect(WireMessage message) {
        WireMessage current = message;
        for (MessageInterceptor interceptor : interceptors) {
            TrafficDecision decision;
            try {
                decision = interceptor.intercept(current);
            } catch (RuntimeException e) {
                decision = onInterceptorFailure(interceptor, current, e);
            }
            if (decision == null) {
                // "No opinion" from a misbehaving interceptor must not stop traffic.
                continue;
            }
            if (!decision.isForward()) {
                return decision;
            }
            current = decision.message();
        }
        return TrafficDecision.forward(current);
    }

    /**
     * What happens when an interceptor throws.
     *
     * <p>Observation and audit are fail-open: a broken recorder must never break a
     * client connection. Policy and rewrite are fail-closed: when the gateway
     * cannot decide or cannot rewrite, it must not forward something it does not
     * understand (AGENTS.md transparency invariants).</p>
     */
    private static TrafficDecision onInterceptorFailure(MessageInterceptor interceptor, WireMessage message,
                                                        RuntimeException failure) {
        return switch (interceptor.phase()) {
            case OBSERVE, AUDIT -> {
                log.warn("{} interceptor failed, continuing (fail-open): {}",
                        interceptor.phase(), failure.toString());
                yield TrafficDecision.forward(message);
            }
            case POLICY, REWRITE -> {
                log.error("{} interceptor failed, denying the message (fail-closed)",
                        interceptor.phase(), failure);
                yield TrafficDecision.deny(message);
            }
        };
    }

    /**
     * Fluent assembly.
     *
     * <p>Each method names the phase it registers, so a rewrite cannot be
     * installed ahead of policy enforcement by accident.</p>
     */
    public static final class Builder {

        private final List<MessageInterceptor> interceptors = new ArrayList<>();

        public Builder add(MessageInterceptor interceptor) {
            interceptors.add(Objects.requireNonNull(interceptor, "interceptor must not be null"));
            return this;
        }

        public Builder observe(Consumer<WireMessage> observer) {
            return add(Interceptors.observe(observer));
        }

        public Builder policy(Function<WireMessage, TrafficDecision> handler) {
            return add(Interceptors.policy(handler));
        }

        public Builder rewrite(UnaryOperator<WireMessage> rewrite) {
            return add(Interceptors.rewrite(rewrite));
        }

        public Builder audit(Consumer<WireMessage> audit) {
            return add(Interceptors.audit(audit));
        }

        public MessagePipeline build() {
            return new MessagePipeline(interceptors);
        }
    }
}
