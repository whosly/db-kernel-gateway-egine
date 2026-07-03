package com.whosly.gateway.adapter.protocol;

import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Immutable protocol frame/message payload.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class ProtocolMessage {

    private final Character type;
    private final byte[] payload;
    private final Integer sequence;

    private ProtocolMessage(Character type, byte[] payload, Integer sequence) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        this.type = type;
        this.payload = Arrays.copyOf(payload, payload.length);
        this.sequence = sequence;
    }

    public static ProtocolMessage typed(char type, byte[] payload) {
        return new ProtocolMessage(type, payload, null);
    }

    public static ProtocolMessage typed(char type, byte[] payload, int sequence) {
        return new ProtocolMessage(type, payload, sequence);
    }

    public static ProtocolMessage untyped(byte[] payload) {
        return new ProtocolMessage(null, payload, null);
    }

    public static ProtocolMessage untyped(byte[] payload, int sequence) {
        return new ProtocolMessage(null, payload, sequence);
    }

    public Optional<Character> type() {
        return Optional.ofNullable(type);
    }

    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public OptionalInt sequence() {
        return sequence == null ? OptionalInt.empty() : OptionalInt.of(sequence);
    }
}
