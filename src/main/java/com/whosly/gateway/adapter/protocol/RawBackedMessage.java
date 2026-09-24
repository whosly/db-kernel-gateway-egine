package com.whosly.gateway.adapter.protocol;

import java.util.Objects;

/**
 * A {@link WireMessage} backed by the bytes that arrived from the socket.
 *
 * <p>An unmutated instance exposes the read buffer directly: no copy, no
 * re-encoding. {@link #withReplacement(byte[])} produces a sibling that carries a
 * replacement payload while keeping the original view available for observation
 * and audit.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class RawBackedMessage implements WireMessage {

    private final TrafficDirection direction;
    private final byte[] originalBytes;
    private final int originalOffset;
    private final int originalLength;
    /** Replacement payload, or {@code null} while the message is unmutated. */
    private final byte[] replacement;

    private RawBackedMessage(TrafficDirection direction, byte[] originalBytes, int originalOffset,
                             int originalLength, byte[] replacement) {
        this.direction = Objects.requireNonNull(direction, "direction must not be null");
        this.originalBytes = Objects.requireNonNull(originalBytes, "bytes must not be null");
        if (originalOffset < 0 || originalLength < 0 || originalOffset + originalLength > originalBytes.length) {
            throw new IllegalArgumentException("message view is outside the read buffer");
        }
        this.originalOffset = originalOffset;
        this.originalLength = originalLength;
        this.replacement = replacement;
    }

    /**
     * Wraps a read buffer view. The buffer is shared with the relay loop, so the
     * message must not outlive the iteration that produced it.
     */
    public static RawBackedMessage of(TrafficDirection direction, byte[] bytes, int offset, int length) {
        return new RawBackedMessage(direction, bytes, offset, length, null);
    }

    @Override
    public TrafficDirection direction() {
        return direction;
    }

    @Override
    public byte[] originalBytes() {
        return originalBytes;
    }

    @Override
    public int originalOffset() {
        return originalOffset;
    }

    @Override
    public int originalLength() {
        return originalLength;
    }

    @Override
    public boolean mutated() {
        return replacement != null;
    }

    @Override
    public byte[] outputBytes() {
        return replacement != null ? replacement : originalBytes;
    }

    @Override
    public int outputOffset() {
        return replacement != null ? 0 : originalOffset;
    }

    @Override
    public int outputLength() {
        return replacement != null ? replacement.length : originalLength;
    }

    @Override
    public WireMessage withReplacement(byte[] replacement) {
        return new RawBackedMessage(direction, originalBytes, originalOffset, originalLength,
                Objects.requireNonNull(replacement, "replacement must not be null"));
    }
}
