package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.masking.ColumnMetadata;
import com.whosly.gateway.masking.MaskedValue;
import com.whosly.gateway.masking.MaskingException;

import java.util.Objects;
import java.util.Set;

/**
 * Wire representation of a masked value in PostgreSQL's binary format.
 *
 * <p>Binary format is chosen per column by the client, and a masked value can only be
 * written if its bytes are valid for the column's type — a client parses the value
 * with the type it asked for, so bytes that are not a valid encoding of that type are
 * a corrupted result set, not a masked one. This class therefore encodes only the
 * types it can reproduce exactly and refuses the rest (rule 8.2).</p>
 *
 * <p>Two cases need no encoding at all, and they are why binary support is mostly
 * about NULL:</p>
 * <ul>
 *   <li><b>NULL</b> is written as the protocol's own NULL marker and is valid for every
 *       type, so nulling a binary column always works;</li>
 *   <li><b>text-family types</b> — and {@code bytea} — have a binary representation that
 *       is simply the value's bytes, so a masked value carries over unchanged.</li>
 * </ul>
 *
 * <p>For fixed-width numeric, temporal, boolean, uuid and unknown types a non-null
 * replacement is refused: producing one would mean re-implementing the type's binary
 * encoding, and guessing it would corrupt the value the client reads.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class PostgreSQLBinaryValues {

    /**
     * Types whose binary representation is the value's bytes, unchanged.
     *
     * <p>{@code jsonb} is deliberately absent: its binary form carries a version byte,
     * which is added when the value is encoded.</p>
     */
    private static final Set<String> RAW_BYTES_TYPES =
            Set.of("text", "varchar", "bpchar", "name", "char", "json", "xml", "bytea");

    /** {@code jsonb}'s binary layout: one version byte, then the JSON text. */
    private static final String JSONB_TYPE_NAME = "jsonb";

    /** The only version of the {@code jsonb} binary layout PostgreSQL sends today. */
    private static final byte JSONB_VERSION = 1;

    private PostgreSQLBinaryValues() {
    }

    /**
     * Encodes a non-null masked value for a column.
     *
     * @param column the column the value will be written to
     * @param value  the masked value; must not be NULL, which needs no encoding
     * @return the bytes to write on the wire
     * @throws MaskingException         when the column's type has no known binary encoding
     * @throws IllegalArgumentException when called with a NULL value, which the caller
     *                                  writes as the protocol's NULL marker instead
     */
    public static byte[] encode(ColumnMetadata column, MaskedValue value) {
        Objects.requireNonNull(column, "column must not be null");
        Objects.requireNonNull(value, "value must not be null");
        if (value.isNull()) {
            throw new IllegalArgumentException("A NULL value is written as the protocol's NULL marker, not encoded: "
                    + column.name());
        }
        if (column.format() == ColumnMetadata.ValueFormat.TEXT) {
            return value.bytes();
        }

        String typeName = column.typeName();
        if (RAW_BYTES_TYPES.contains(typeName)) {
            return value.bytes();
        }
        if (JSONB_TYPE_NAME.equals(typeName)) {
            return withJsonbVersion(value.bytes());
        }
        throw new MaskingException("Binary values of type " + typeName
                + " cannot carry a non-null masked value: column " + column.name());
    }

    private static byte[] withJsonbVersion(byte[] masked) {
        byte[] encoded = new byte[masked.length + 1];
        encoded[0] = JSONB_VERSION;
        System.arraycopy(masked, 0, encoded, 1, masked.length);
        return encoded;
    }
}
