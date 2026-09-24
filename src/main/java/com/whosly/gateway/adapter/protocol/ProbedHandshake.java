package com.whosly.gateway.adapter.protocol;

import java.util.Objects;

/**
 * Result of an early client handshake peek used for backend routing.
 *
 * <p>Protocol adapters may consume the first client packet(s) to learn username
 * / database before {@link BackendProvider#acquire(RoutingContext)}. Those exact
 * bytes must be replayed into the client→backend stream so transparency is
 * preserved (observation and the target still see the original wire bytes).</p>
 *
 * <p>MySQL, PostgreSQL, and future Oracle / SQL Server adapters share this type;
 * only the probe implementation is protocol-specific.</p>
 */
public final class ProbedHandshake {

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final ProbedHandshake EMPTY = new ProbedHandshake(RoutingContext.empty(), EMPTY_BYTES);

    private final RoutingContext context;
    private final byte[] replayToBackend;

    private ProbedHandshake(RoutingContext context, byte[] replayToBackend) {
        this.context = Objects.requireNonNull(context, "context");
        this.replayToBackend = Objects.requireNonNull(replayToBackend, "replayToBackend");
    }

    /** No identity known and nothing consumed from the client. */
    public static ProbedHandshake empty() {
        return EMPTY;
    }

    /**
     * @param context         identity for {@link BackendProvider#acquire(RoutingContext)}
     * @param replayToBackend exact client bytes already read; may be empty
     */
    public static ProbedHandshake of(RoutingContext context, byte[] replayToBackend) {
        Objects.requireNonNull(context, "context");
        if (replayToBackend == null || replayToBackend.length == 0) {
            return context.isEmpty() ? empty() : new ProbedHandshake(context, EMPTY_BYTES);
        }
        byte[] copy = new byte[replayToBackend.length];
        System.arraycopy(replayToBackend, 0, copy, 0, replayToBackend.length);
        return new ProbedHandshake(context, copy);
    }

    public RoutingContext context() {
        return context;
    }

    /** Exact bytes removed from the client input that must be forwarded first. */
    public byte[] replayToBackend() {
        if (replayToBackend.length == 0) {
            return EMPTY_BYTES;
        }
        byte[] copy = new byte[replayToBackend.length];
        System.arraycopy(replayToBackend, 0, copy, 0, replayToBackend.length);
        return copy;
    }

    public boolean hasReplay() {
        return replayToBackend.length > 0;
    }
}
