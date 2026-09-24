package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.masking.ColumnMetadata;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Values of one MySQL binary-protocol row, in both directions.
 *
 * <p>A binary row — the shape a prepared statement's result takes — is
 * {@code 0x00}, a null bitmap, then the values of every column that is not NULL, each
 * encoded by its own type. Two things follow from that layout and are why this class
 * exists instead of a small addition to the text row:</p>
 * <ul>
 *   <li>a value's position depends on the width of every value before it, so a column
 *       cannot be rewritten without walking the row — the type table in
 *       {@link MySQLBinaryValues} is what makes that walk possible;</li>
 *   <li>NULL is not a marker in the value stream but a <em>bit</em> in a fixed-size
 *       bitmap, so nulling a column removes bytes and sets a bit at the same time.</li>
 * </ul>
 *
 * <p>The bitmap is addressed with an offset of two bits, which is not padding but the
 * protocol's own rule: bit {@code n} of the row belongs to column {@code n - 2}.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class MySQLBinaryRow {

    /** Bits of the null bitmap that precede the first column. */
    private static final int BITMAP_COLUMN_OFFSET = 2;

    /** The payload's first byte for a value row, as opposed to EOF/OK/ERR. */
    private static final int ROW_HEADER = 0x00;

    private MySQLBinaryRow() {
    }

    /**
     * Parses a binary row payload.
     *
     * @param payload buffer holding the row packet's payload
     * @param offset  payload start, past the packet header
     * @param length  payload length
     * @param columns columns of the result set, in order
     * @return the values in column order, where {@code null} means SQL NULL and each
     *         other entry carries the value's wire bytes including any length prefix;
     *         empty when the payload is not a valid row or uses a type whose layout is
     *         not known
     */
    public static Optional<List<byte[]>> parse(byte[] payload, int offset, int length,
                                               List<ColumnMetadata> columns) {
        if (payload == null || offset < 0 || length < 2 || offset + length > payload.length
                || columns.isEmpty()) {
            return Optional.empty();
        }
        if ((payload[offset] & 0xFF) != ROW_HEADER) {
            return Optional.empty();
        }

        int end = offset + length;
        int bitmapOffset = offset + 1;
        int bitmapLength = bitmapLength(columns.size());
        if (bitmapOffset + bitmapLength > end) {
            return Optional.empty();
        }

        int cursor = bitmapOffset + bitmapLength;
        List<byte[]> values = new ArrayList<>(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            if (isNull(payload, bitmapOffset, index)) {
                values.add(null);
                continue;
            }
            Optional<Integer> valueLength =
                    MySQLBinaryValues.valueLength(columns.get(index).typeName(), payload, cursor, end);
            if (valueLength.isEmpty() || valueLength.get() > end - cursor) {
                return Optional.empty();
            }
            byte[] value = new byte[valueLength.get()];
            System.arraycopy(payload, cursor, value, 0, valueLength.get());
            values.add(value);
            cursor += valueLength.get();
        }

        /*
         * The values must account for the payload exactly. A type whose layout was read
         * wrongly does not fail loudly — it silently shifts every following value into the
         * wrong column — so the leftover is checked rather than ignored.
         */
        if (cursor != end) {
            return Optional.empty();
        }
        return Optional.of(values);
    }

    /**
     * Encodes a row from values already in their wire form.
     *
     * @param columns columns of the result set, in order
     * @param values  wire bytes per column, where {@code null} means SQL NULL; every
     *                other entry must already be encoded for its column's type
     * @return the row payload, header byte and null bitmap included
     */
    public static byte[] encode(List<ColumnMetadata> columns, List<byte[]> values) {
        if (columns.size() != values.size()) {
            throw new IllegalArgumentException("One value per column is required: " + values.size()
                    + " values for " + columns.size() + " columns");
        }

        byte[] bitmap = new byte[bitmapLength(columns.size())];
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int index = 0; index < columns.size(); index++) {
            byte[] value = values.get(index);
            if (value == null) {
                setNull(bitmap, index);
                continue;
            }
            body.writeBytes(value);
        }

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(ROW_HEADER);
        payload.writeBytes(bitmap);
        payload.writeBytes(body.toByteArray());
        return payload.toByteArray();
    }

    /** Bytes the null bitmap occupies for this many columns. */
    public static int bitmapLength(int columnCount) {
        return (columnCount + 7 + BITMAP_COLUMN_OFFSET) / 8;
    }

    private static boolean isNull(byte[] payload, int bitmapOffset, int columnIndex) {
        int bit = columnIndex + BITMAP_COLUMN_OFFSET;
        return (payload[bitmapOffset + bit / 8] & (1 << (bit % 8))) != 0;
    }

    private static void setNull(byte[] bitmap, int columnIndex) {
        int bit = columnIndex + BITMAP_COLUMN_OFFSET;
        bitmap[bit / 8] |= (byte) (1 << (bit % 8));
    }
}
