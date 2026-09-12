package com.whosly.gateway.adapter.protocol;

/**
 * Reports where complete protocol messages end inside a byte window.
 *
 * <p>Framing knowledge stays in the protocol layer, which is the only place that
 * tracks connection phase, opaque tunnels and startup families. The data path
 * asks instead of re-implementing framing, so a protocol has exactly one place
 * that decides what a message is and the two cannot drift apart (rule 2.10).</p>
 *
 * <p>Implementations must be pure with respect to the given window: they may
 * consult protocol state, but they must not buffer the window themselves, because
 * the caller owns the buffer and the hold timeout.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@FunctionalInterface
public interface MessageBounder {

    /**
     * Finds the end offsets of the complete messages in the window.
     *
     * @param bytes  buffer holding the window
     * @param offset start of the window
     * @param length number of bytes in the window
     * @return end offsets relative to {@code offset}, in ascending order. Bytes
     *         at or beyond the last offset belong to a message that is not
     *         complete yet, so the caller must hold them. An empty array means
     *         the window does not contain a single complete message, and
     *         {@code null} means framing is not available at all — an opaque
     *         tunnel, or bytes that cannot be a valid message — in which case the
     *         caller must stop holding and forward everything immediately
     *         (rule 2.10: an encrypted stream has no cleartext framing to trust).
     */
    int[] completeMessageEnds(byte[] bytes, int offset, int length);
}
