package com.whosly.gateway.masking;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * One value as it appears on the wire, before or after masking.
 *
 * <p>Bytes are kept exactly as the protocol carries them: masking must not
 * silently change a value's representation, only its content. Text helpers exist
 * for the connection charset; they are never used to re-encode a value the rule
 * did not intend to change.</p>
 *
 * @param bytes     wire representation; empty for NULL
 * @param nullValue whether the value is NULL
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public record MaskedValue(byte[] bytes, boolean nullValue) {

    public MaskedValue {
        Objects.requireNonNull(bytes, "bytes must not be null");
    }

    public static MaskedValue of(byte[] bytes) {
        return new MaskedValue(bytes, false);
    }

    public static MaskedValue ofText(String text, Charset charset) {
        Objects.requireNonNull(text, "text must not be null");
        return new MaskedValue(text.getBytes(charset), false);
    }

    /** A NULL value: no payload, and a wire representation the writer encodes. */
    public static MaskedValue ofNull() {
        return new MaskedValue(new byte[0], true);
    }

    public boolean isNull() {
        return nullValue;
    }

    /** Number of wire bytes this value occupies. */
    public int length() {
        return nullValue ? 0 : bytes.length;
    }

    public String asText(Charset charset) {
        return Objects.requireNonNull(charset, "charset must not be null")
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
    }
}
