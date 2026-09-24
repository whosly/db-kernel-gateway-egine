package com.whosly.gateway.adapter.protocol;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

/**
 * Holds the tail of a read until it forms complete protocol messages.
 *
 * <p>The data path forwards every read immediately unless a rewrite needs whole
 * messages. When it does, this buffer assembles them: the protocol layer reports
 * which bytes are complete ({@link MessageBounder}) and only the trailing partial
 * message is kept. Memory is bounded by one message per direction, and the time a
 * partial message may wait is bounded too ({@link RewriteLimits}), so a rewrite
 * can never turn into an unbounded stall or an unbounded buffer.</p>
 *
 * <p>Exceeding either bound raises {@link RewriteLimitException}; the caller
 * rejects the connection rather than forwarding bytes it could not rewrite.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class MessageHoldBuffer {

    private final MessageBounder bounder;
    private final RewriteLimits limits;
    private final ByteArrayOutputStream held = new ByteArrayOutputStream();
    /** When the currently held partial message started, or 0 when nothing is held. */
    private long heldSinceNanos;

    public MessageHoldBuffer(MessageBounder bounder, RewriteLimits limits) {
        this.bounder = Objects.requireNonNull(bounder, "bounder must not be null");
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
    }

    /** Bytes currently held: a prefix of one message that is not complete yet. */
    public int heldBytes() {
        return held.size();
    }

    /** True while a partial message is being assembled. */
    public boolean isHolding() {
        return held.size() > 0;
    }

    /** Milliseconds the held partial message has been waiting; 0 when nothing is held. */
    public long heldMillis() {
        return heldSinceNanos == 0L ? 0L : (System.nanoTime() - heldSinceNanos) / 1_000_000L;
    }

    /**
     * Appends a read and returns the window of bytes that may be processed.
     *
     * <p>The returned window is backed by this buffer and is only valid until the
     * next call. Complete messages are the ranges between consecutive
     * {@link HeldWindow#messageEnds()}; anything after the last end stays held for
     * the next call.</p>
     *
     * @throws RewriteLimitException when the partial message exceeds either bound
     */
    public HeldWindow append(byte[] bytes, int offset, int length) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        boolean wasHolding = isHolding();
        if (length > 0) {
            held.write(bytes, offset, length);
        }

        byte[] buffered = held.toByteArray();
        int[] ends = bounder.completeMessageEnds(buffered, 0, buffered.length);
        if (ends == null) {
            /*
             * Framing is not available (opaque tunnel, or bytes that cannot form a
             * valid message). Nothing may be held: the window is handed over whole so
             * the caller forwards it immediately, which is what rule 2.10 requires
             * once a session stops being parseable.
             */
            held.reset();
            heldSinceNanos = 0L;
            return new HeldWindow(buffered, buffered.length == 0 ? new int[0] : new int[]{buffered.length});
        }
        int completeBytes = ends.length == 0 ? 0 : ends[ends.length - 1];

        if (completeBytes <= 0) {
            // No message finished yet, so the whole window is still partial.
            if (!wasHolding) {
                heldSinceNanos = System.nanoTime();
            }
            checkLimits(buffered.length);
            return new HeldWindow(buffered, ends);
        }

        int tailLength = buffered.length - completeBytes;
        if (tailLength > 0) {
            checkLimits(tailLength);
        }

        held.reset();
        if (tailLength > 0) {
            held.write(buffered, completeBytes, tailLength);
            if (!wasHolding) {
                // The tail is the beginning of the next message: start its clock.
                heldSinceNanos = System.nanoTime();
            }
        } else {
            heldSinceNanos = 0L;
        }
        return new HeldWindow(buffered, ends);
    }

    /**
     * Releases whatever is held, for connection teardown.
     *
     * <p>A truncated tail cannot be rewritten and the session is ending, so it is
     * handed back to be written as received rather than dropped.</p>
     */
    public byte[] drain() {
        byte[] remaining = held.toByteArray();
        held.reset();
        heldSinceNanos = 0L;
        return remaining;
    }

    private void checkLimits(int pendingBytes) {
        if (limits.exceedsMessageSize(pendingBytes)) {
            throw RewriteLimitException.messageTooLarge(pendingBytes, limits);
        }
        long heldMillis = heldMillis();
        if (limits.exceedsHoldTime(heldMillis)) {
            throw RewriteLimitException.holdTimeout(heldMillis, limits);
        }
    }

    /**
     * A window of bytes that may contain complete messages.
     *
     * @param bytes       buffer contents; valid until the next {@link #append}
     * @param messageEnds end offsets of the complete messages, ascending
     * @author yueny09@163.com codealy
     * @since 2026-07-02
     */
    public record HeldWindow(byte[] bytes, int[] messageEnds) {

        /** Number of leading bytes that form complete messages. */
        public int completeBytes() {
            return messageEnds.length == 0 ? 0 : messageEnds[messageEnds.length - 1];
        }

        /** Number of complete messages in this window. */
        public int messageCount() {
            return messageEnds.length;
        }

        /** True when the window contains no complete message at all. */
        public boolean isEmpty() {
            return messageEnds.length == 0;
        }
    }
}
