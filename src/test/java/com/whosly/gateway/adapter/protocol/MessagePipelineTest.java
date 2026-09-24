package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class MessagePipelineTest {

    @Test
    void runsInterceptorsInPhaseOrderRegardlessOfRegistrationOrder() {
        List<String> order = new CopyOnWriteArrayList<>();
        MessageInterceptor rewrite = recording("rewrite", InterceptorPhase.REWRITE, order);
        MessageInterceptor observe = recording("observe", InterceptorPhase.OBSERVE, order);
        MessageInterceptor audit = recording("audit", InterceptorPhase.AUDIT, order);
        MessageInterceptor policy = recording("policy", InterceptorPhase.POLICY, order);

        MessagePipeline pipeline = MessagePipeline.of(rewrite, observe, audit, policy);

        TrafficDecision decision = pipeline.inspect(message());

        assertThat(order).containsExactly("observe", "policy", "rewrite", "audit");
        assertThat(decision.isForward()).isTrue();
        assertThat(decision.message().mutated()).isFalse();
    }

    @Test
    void stopsTheChainWhenADecisionIsNotForward() {
        List<String> order = new CopyOnWriteArrayList<>();
        NamedInterceptor deny = new NamedInterceptor("deny", InterceptorPhase.POLICY, message -> {
            order.add("deny");
            return TrafficDecision.deny(message);
        });
        MessageInterceptor afterDeny = recording("after", InterceptorPhase.REWRITE, order);

        MessagePipeline pipeline = MessagePipeline.of(afterDeny, deny);

        TrafficDecision decision = pipeline.inspect(message());

        assertThat(decision.action()).isEqualTo(TrafficAction.DENY);
        assertThat(order).containsExactly("deny");
    }

    @Test
    void propagatesAReplacementToTheFinalDecision() {
        NamedInterceptor observer = new NamedInterceptor("observe", InterceptorPhase.OBSERVE,
                message -> TrafficDecision.forward(message));
        NamedInterceptor rewrite = new NamedInterceptor("rewrite", InterceptorPhase.REWRITE,
                message -> TrafficDecision.forward(message.withReplacement(new byte[]{9, 9})));

        MessagePipeline pipeline = MessagePipeline.of(rewrite, observer);

        TrafficDecision decision = pipeline.inspect(message());

        assertThat(decision.isForward()).isTrue();
        assertThat(decision.isMutated()).isTrue();
        assertThat(decision.message().outputBytes()).containsExactly(9, 9);
        // Observation and audit can still see what actually arrived.
        assertThat(decision.message().originalBytes()).containsExactly(1, 2, 3);
        assertThat(decision.message().originalLength()).isEqualTo(3);
    }

    @Test
    void builderRegistersEachInterceptorWithItsPhase() {
        List<String> order = new CopyOnWriteArrayList<>();
        MessagePipeline pipeline = MessagePipeline.builder()
                .rewrite(message -> {
                    order.add("rewrite");
                    return message;
                })
                .observe(message -> order.add("observe"))
                .policy(message -> {
                    order.add("policy");
                    return TrafficDecision.forward(message);
                })
                .audit(message -> order.add("audit"))
                .build();

        TrafficDecision decision = pipeline.inspect(message());

        assertThat(order).containsExactly("observe", "policy", "rewrite", "audit");
        assertThat(decision.isForward()).isTrue();
    }

    @Test
    void keepsForwardingWhenAnObserveInterceptorFails() {
        MessagePipeline pipeline = MessagePipeline.of(
                Interceptors.observe(message -> {
                    throw new IllegalStateException("audit sink unavailable");
                }));

        TrafficDecision decision = pipeline.inspect(message());

        assertThat(decision.isForward()).isTrue();
        assertThat(decision.message().mutated()).isFalse();
    }

    @Test
    void keepsForwardingWhenAnAuditInterceptorFails() {
        MessagePipeline pipeline = MessagePipeline.of(
                Interceptors.audit(message -> {
                    throw new IllegalStateException("audit sink unavailable");
                }));

        assertThat(pipeline.inspect(message()).isForward()).isTrue();
    }

    @Test
    void deniesWhenAPolicyInterceptorFails() {
        MessagePipeline pipeline = MessagePipeline.of(
                Interceptors.policy(message -> {
                    throw new IllegalStateException("policy unavailable");
                }));

        assertThat(pipeline.inspect(message()).action()).isEqualTo(TrafficAction.DENY);
    }

    @Test
    void deniesWhenARewriteInterceptorFails() {
        MessagePipeline pipeline = MessagePipeline.of(
                Interceptors.rewrite(message -> {
                    throw new IllegalStateException("rewriter unavailable");
                }));

        assertThat(pipeline.inspect(message()).action()).isEqualTo(TrafficAction.DENY);
    }

    @Test
    void ignoresAnInterceptorThatReturnsNoDecision() {
        NamedInterceptor silent = new NamedInterceptor("silent", InterceptorPhase.OBSERVE, message -> null);
        MessagePipeline pipeline = MessagePipeline.of(silent);

        assertThat(pipeline.inspect(message()).isForward()).isTrue();
    }

    private static WireMessage message() {
        return RawBackedMessage.of(TrafficDirection.CLIENT_TO_TARGET, new byte[]{1, 2, 3}, 0, 3);
    }

    private static MessageInterceptor recording(String name, InterceptorPhase phase, List<String> order) {
        return new NamedInterceptor(name, phase, message -> {
            order.add(name);
            return TrafficDecision.forward(message);
        });
    }

    private record NamedInterceptor(String name, InterceptorPhase phase,
                                    Function<WireMessage, TrafficDecision> handler)
            implements MessageInterceptor {

        @Override
        public TrafficDecision intercept(WireMessage message) {
            return handler.apply(message);
        }
    }
}
