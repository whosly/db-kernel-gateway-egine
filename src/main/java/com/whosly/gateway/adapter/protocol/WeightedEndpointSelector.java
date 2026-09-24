package com.whosly.gateway.adapter.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

/**
 * Picks a primary endpoint by weight, then returns a try-order for failover:
 * selected primary first, remaining endpoints in original list order.
 *
 * <p>Deterministic when the caller supplies a seeded {@link IntUnaryOperator}
 * that maps {@code bound → [0, bound)} (same contract as {@link java.util.Random#nextInt(int)}).</p>
 */
public final class WeightedEndpointSelector {

    private WeightedEndpointSelector() {
    }

    public static List<BackendEndpoint> orderForAttempt(List<WeightedEndpoint> endpoints) {
        return orderForAttempt(endpoints, ThreadLocalRandom.current()::nextInt);
    }

    public static List<BackendEndpoint> orderForAttempt(List<WeightedEndpoint> endpoints,
                                                        IntUnaryOperator nextIntBound) {
        Objects.requireNonNull(endpoints, "endpoints must not be null");
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints must not be empty");
        }
        Objects.requireNonNull(nextIntBound, "nextIntBound must not be null");

        int totalWeight = 0;
        for (WeightedEndpoint endpoint : endpoints) {
            totalWeight += endpoint.weight();
        }
        int slot = Math.floorMod(nextIntBound.applyAsInt(totalWeight), totalWeight);
        int chosen = 0;
        int cumulative = 0;
        for (int i = 0; i < endpoints.size(); i++) {
            cumulative += endpoints.get(i).weight();
            if (slot < cumulative) {
                chosen = i;
                break;
            }
        }

        List<BackendEndpoint> order = new ArrayList<>(endpoints.size());
        order.add(endpoints.get(chosen).endpoint());
        for (int i = 0; i < endpoints.size(); i++) {
            if (i == chosen) {
                continue;
            }
            order.add(endpoints.get(i).endpoint());
        }
        return List.copyOf(order);
    }

    /** Selects a single endpoint by weight (no failover reorder). */
    public static BackendEndpoint pickOne(List<WeightedEndpoint> endpoints, IntUnaryOperator nextIntBound) {
        return orderForAttempt(endpoints, nextIntBound).get(0);
    }
}
