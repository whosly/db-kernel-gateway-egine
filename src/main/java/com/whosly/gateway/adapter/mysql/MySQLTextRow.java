package com.whosly.gateway.adapter.mysql;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Values of one MySQL text-protocol row, in both directions.
 *
 * <p>A text row is a bare concatenation of length-encoded strings, with no column
 * count and no type information: the column count comes from the result-set
 * header and the types from the column definitions. That is why a row can only be
 * interpreted together with the metadata observed for its result set — and why a
 * row whose value count disagrees with that metadata must be rejected instead of
 * guessed at.</p>
 *
 * <p>Values are kept as raw bytes. Masking replaces a value's content, and
 * re-encoding it here must never change the representation of the values a rule
 * did not touch.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class MySQLTextRow {

    /** The text protocol's NULL marker: a length prefix that means "no value". */
    private static final int NULL_MARKER = 0xFB;

    /** Length prefixes that introduce a longer length field. */
    private static final int TWO_BYTE_PREFIX = 0xFC;
    private static final int THREE_BYTE_PREFIX = 0xFD;
    private static final int EIGHT_BYTE_PREFIX = 0xFE;

    /** Largest value a single-byte length can describe. */
    private static final int SINGLE_BYTE_LIMIT = 251;
    private static final int TWO_BYTE_LIMIT = 65_535;
    private static final int THREE_BYTE_LIMIT = 0xFF_FFFF;

    private MySQLTextRow() {
    }

    /**
     * Parses a row payload.
     *
     * @param payload buffer holding the row
     * @param offset  payload start, past the packet header
     * @param length  payload length
     * @return the values in column order, where {@code null} means SQL NULL; empty
     *         when the payload is not a valid text row
     */
    public static Optional<List<byte[]>> parse(byte[] payload, int offset, int length) {
        if (payload == null || offset < 0 || length < 0 || offset + length > payload.length) {
            return Optional.empty();
        }

        List<byte[]> values = new ArrayList<>();
        int end = offset + length;
        int cursor = offset;
        while (cursor < end) {
            int prefix = payload[cursor] & 0xFF;
            if (prefix == NULL_MARKER) {
                values.add(null);
                cursor++;
                continue;
            }

            int headerLength = lengthFieldSize(prefix);
            long valueLength = headerLength < 0 ? -1 : readLength(payload, cursor, prefix, end);
            if (valueLength < 0 || valueLength > end - cursor - headerLength) {
                return Optional.empty();
            }

            byte[] value = new byte[(int) valueLength];
            System.arraycopy(payload, cursor + headerLength, value, 0, (int) valueLength);
            values.add(value);
            cursor += headerLength + (int) valueLength;
        }
        return Optional.of(values);
    }

    /**
     * Encodes values back into a row payload, preserving the encoding of untouched
     * values exactly.
     *
     * @param values values in column order, where {@code null} means SQL NULL
     * @return the row payload
     */
    public static byte[] encode(List<byte[]> values) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (byte[] value : values) {
            if (value == null) {
                payload.write(NULL_MARKER);
                continue;
            }
            writeLength(payload, value.length);
            payload.writeBytes(value);
        }
        return payload.toByteArray();
    }

    /** Bytes a value of this length needs in front of its content. */
    private static int lengthFieldSize(int prefix) {
        if (prefix < NULL_MARKER) {
            return 1;
        }
        return switch (prefix) {
            case TWO_BYTE_PREFIX -> 3;
            case THREE_BYTE_PREFIX -> 4;
            case EIGHT_BYTE_PREFIX -> 9;
            // 0xFB is NULL (handled by the caller) and 0xFF cannot appear here.
            default -> -1;
        };
    }

    /**
     * Reads a length-encoded integer at {@code cursor}.
     *
     * <p>Returns both the value and how many bytes its prefix occupies, which is what a
     * binary row needs to skip a length-encoded value.</p>
     *
     * @return the prefix size and value, or empty when the bytes are not a valid prefix
     */
    static Optional<LengthPrefix> readLengthEncodedLength(byte[] payload, int cursor, int end) {
        if (payload == null || cursor < 0 || cursor >= end) {
            return Optional.empty();
        }
        int prefix = payload[cursor] & 0xFF;
        int headerLength = lengthFieldSize(prefix);
        if (headerLength < 0 || cursor + headerLength > end) {
            return Optional.empty();
        }
        if (prefix < NULL_MARKER) {
            return Optional.of(new LengthPrefix(prefix, 1));
        }
        long length = 0;
        for (int index = headerLength - 1; index >= 1; index--) {
            length = (length << 8) | (payload[cursor + index] & 0xFF);
        }
        return Optional.of(new LengthPrefix(length, headerLength));
    }

    /** A length-encoded integer as it appears on the wire. */
    record LengthPrefix(long value, int length) {
    }

    private static long readLength(byte[] payload, int cursor, int prefix, int end) {
        int headerLength = lengthFieldSize(prefix);
        if (headerLength < 0 || cursor + headerLength > end) {
            return -1;
        }
        if (prefix < NULL_MARKER) {
            return prefix;
        }
        long length = 0;
        for (int index = headerLength - 1; index >= 1; index--) {
            length = (length << 8) | (payload[cursor + index] & 0xFF);
        }
        return length;
    }

    /**
     * Writes a length-encoded integer.
     *
     * <p>Package-private because the binary row's string family uses the same prefix
     * scheme: one implementation of the encoding, not two that can drift.</p>
     */
    static void writeLength(ByteArrayOutputStream payload, int length) {
        if (length < SINGLE_BYTE_LIMIT) {
            payload.write(length);
        } else if (length <= TWO_BYTE_LIMIT) {
            payload.write(TWO_BYTE_PREFIX);
            payload.write(length & 0xFF);
            payload.write((length >> 8) & 0xFF);
        } else if (length <= THREE_BYTE_LIMIT) {
            payload.write(THREE_BYTE_PREFIX);
            payload.write(length & 0xFF);
            payload.write((length >> 8) & 0xFF);
            payload.write((length >> 16) & 0xFF);
        } else {
            payload.write(EIGHT_BYTE_PREFIX);
            for (int shift = 0; shift < 64; shift += 8) {
                payload.write((int) ((long) length >> shift) & 0xFF);
            }
        }
    }
}
