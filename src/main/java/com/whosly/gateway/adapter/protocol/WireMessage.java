package com.whosly.gateway.adapter.protocol;

/**
 * One message as it travels through the data path.
 *
 * <p>The gateway forwards bytes verbatim unless something explicitly rewrites
 * them. This contract makes that default explicit and cheap: an unmutated
 * message keeps the original view, so the relay writes the very bytes that
 * arrived and never re-serializes for convenience (rule 2.10).</p>
 *
 * <p>A rewrite must go through {@link #withReplacement(byte[])}, which returns a
 * new message and leaves the original view intact. That keeps the blast radius of
 * a serializer bug limited to the messages a rewrite actually intended to
 * change.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public interface WireMessage {

    /** Direction the message is travelling in. */
    TrafficDirection direction();

    /** Bytes exactly as read from the source socket; never modified. */
    byte[] originalBytes();

    int originalOffset();

    int originalLength();

    /** True only when a rewrite produced a replacement payload. */
    boolean mutated();

    /** Bytes to forward: the original view when unmutated, else the replacement. */
    byte[] outputBytes();

    int outputOffset();

    int outputLength();

    /**
     * Returns a mutated sibling carrying {@code replacement} as its output.
     *
     * @param replacement payload to forward instead of the original bytes
     */
    WireMessage withReplacement(byte[] replacement);
}
