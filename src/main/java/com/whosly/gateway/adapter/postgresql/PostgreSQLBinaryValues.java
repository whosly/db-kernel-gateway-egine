package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.masking.ColumnMetadata;
import com.whosly.gateway.masking.MaskedValue;
import com.whosly.gateway.masking.MaskingException;

import java.nio.charset.StandardCharsets;
import java.util.Map;
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
 * <p>Cases that need no or little encoding:</p>
 * <ul>
 *   <li><b>NULL</b> is written as the protocol's own NULL marker and is valid for every
 *       type, so nulling a binary column always works;</li>
 *   <li><b>text-family types</b> — and {@code bytea} — have a binary representation that
 *       is simply the value's bytes, so a masked value carries over unchanged;</li>
 *   <li><b>fixed-width numeric / boolean</b> ({@code int2}/{@code int4}/{@code int8},
 *       {@code float4}/{@code float8}, {@code bool}) are rewritten as big-endian IEEE /
 *       two's complement values parsed from the masked text.</li>
 * </ul>
 *
 * <p>Temporal, uuid, numeric (arbitrary precision), money and unknown types still refuse
 * a non-null replacement: producing one would mean re-implementing the type's binary
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

    /** Fixed-width integer types written big-endian. */
    private static final Map<String, Integer> INTEGER_TYPES = Map.of(
            "int2", 2,
            "int4", 4,
            "int8", 8);

    /** IEEE-754 floating types written big-endian. */
    private static final Map<String, Integer> FLOATING_TYPES = Map.of(
            "float4", 4,
            "float8", 8);

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
        if ("bool".equals(typeName)) {
            return encodeBool(value.bytes(), column.name());
        }
        Integer integerWidth = INTEGER_TYPES.get(typeName);
        if (integerWidth != null) {
            return encodeInteger(typeName, integerWidth, value.bytes(), column.name());
        }
        Integer floatWidth = FLOATING_TYPES.get(typeName);
        if (floatWidth != null) {
            return encodeFloating(floatWidth, value.bytes(), column.name());
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

    private static byte[] encodeBool(byte[] masked, String columnName) {
        String text = new String(masked, StandardCharsets.US_ASCII).trim();
        if ("t".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text) || "1".equals(text)) {
            return new byte[]{1};
        }
        if ("f".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text) || "0".equals(text)) {
            return new byte[]{0};
        }
        throw new MaskingException("Masked value is not a boolean, so it cannot be written to column "
                + columnName);
    }

    private static byte[] encodeInteger(String typeName, int width, byte[] masked, String columnName) {
        long parsed;
        try {
            parsed = Long.parseLong(new String(masked, StandardCharsets.US_ASCII).trim());
        } catch (NumberFormatException e) {
            throw new MaskingException("Masked value is not an integer, so it cannot be written to the "
                    + typeName + " column " + columnName);
        }
        long limit = 1L << (width * 8 - 1);
        if (parsed < -limit || parsed > limit - 1) {
            throw new MaskingException("Masked value " + parsed + " does not fit a signed " + typeName
                    + " column " + columnName);
        }
        return bigEndian(parsed, width);
    }

    private static byte[] encodeFloating(int width, byte[] masked, String columnName) {
        double parsed;
        try {
            parsed = Double.parseDouble(new String(masked, StandardCharsets.US_ASCII).trim());
        } catch (NumberFormatException e) {
            throw new MaskingException("Masked value is not a number, so it cannot be written to column "
                    + columnName);
        }
        if (width == 4) {
            return bigEndian(Float.floatToIntBits((float) parsed) & 0xFFFFFFFFL, 4);
        }
        return bigEndian(Double.doubleToLongBits(parsed), 8);
    }

    private static byte[] bigEndian(long value, int width) {
        byte[] bytes = new byte[width];
        for (int index = 0; index < width; index++) {
            bytes[width - 1 - index] = (byte) ((value >> (8 * index)) & 0xFF);
        }
        return bytes;
    }
}
