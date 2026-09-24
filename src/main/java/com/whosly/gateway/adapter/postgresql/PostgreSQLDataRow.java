package com.whosly.gateway.adapter.postgresql;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Values of one PostgreSQL {@code DataRow}, in both directions.
 *
 * <p>A {@code DataRow} carries its own field count and an explicit length per
 * value, where {@code -1} means SQL NULL. Nothing about the values' types is on
 * the wire, which is why a row can only be interpreted next to the
 * {@code RowDescription} of its result set — and why a row whose field count
 * disagrees with that description must be rejected instead of guessed at.</p>
 *
 * <p>Values are kept as raw bytes: masking replaces a value's content, and
 * re-encoding must not change the representation of the values a rule did not
 * touch.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class PostgreSQLDataRow {

    /** Length prefix of a NULL value. */
    private static final int NULL_LENGTH = -1;

    private PostgreSQLDataRow() {
    }

    /**
     * Parses a row payload.
     *
     * @param payload buffer holding the message
     * @param offset  payload start, past the type byte and length
     * @param length  payload length
     * @return the values in field order, where {@code null} means SQL NULL; empty
     *         when the payload is not a valid row
     */
    public static Optional<List<byte[]>> parse(byte[] payload, int offset, int length) {
        if (payload == null || offset < 0 || length < 2 || offset + length > payload.length) {
            return Optional.empty();
        }

        int end = offset + length;
        int fieldCount = ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
        List<byte[]> values = new ArrayList<>(fieldCount);
        int cursor = offset + 2;
        for (int index = 0; index < fieldCount; index++) {
            if (cursor + 4 > end) {
                return Optional.empty();
            }
            int valueLength = ((payload[cursor] & 0xFF) << 24)
                    | ((payload[cursor + 1] & 0xFF) << 16)
                    | ((payload[cursor + 2] & 0xFF) << 8)
                    | (payload[cursor + 3] & 0xFF);
            cursor += 4;
            if (valueLength == NULL_LENGTH) {
                values.add(null);
                continue;
            }
            if (valueLength < 0 || valueLength > end - cursor) {
                return Optional.empty();
            }
            byte[] value = new byte[valueLength];
            System.arraycopy(payload, cursor, value, 0, valueLength);
            values.add(value);
            cursor += valueLength;
        }
        return Optional.of(values);
    }

    /**
     * Encodes values back into a row payload.
     *
     * @param values values in field order, where {@code null} means SQL NULL
     * @return the row payload, without the type byte and length
     */
    public static byte[] encode(List<byte[]> values) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write((values.size() >> 8) & 0xFF);
        payload.write(values.size() & 0xFF);
        for (byte[] value : values) {
            if (value == null) {
                payload.writeBytes(int4(NULL_LENGTH));
                continue;
            }
            payload.writeBytes(int4(value.length));
            payload.writeBytes(value);
        }
        return payload.toByteArray();
    }

    private static byte[] int4(int value) {
        return new byte[]{
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)};
    }
}
