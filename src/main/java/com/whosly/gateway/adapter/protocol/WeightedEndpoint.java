package com.whosly.gateway.adapter.protocol;

import java.util.Objects;

/** One backend endpoint with an optional positive routing weight (default 1). */
public final class WeightedEndpoint {

    private final BackendEndpoint endpoint;
    private final int weight;

    public WeightedEndpoint(BackendEndpoint endpoint, int weight) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        if (weight <= 0) {
            throw new IllegalArgumentException("weight must be positive: " + weight);
        }
        this.weight = weight;
    }

    public WeightedEndpoint(BackendEndpoint endpoint) {
        this(endpoint, 1);
    }

    public BackendEndpoint endpoint() {
        return endpoint;
    }

    public int weight() {
        return weight;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WeightedEndpoint that)) {
            return false;
        }
        return weight == that.weight && endpoint.equals(that.endpoint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(endpoint, weight);
    }

    @Override
    public String toString() {
        return weight == 1 ? endpoint.toString() : endpoint + ":" + weight;
    }
}
