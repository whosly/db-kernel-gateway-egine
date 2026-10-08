package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.masking.ColumnMetadata;
import com.whosly.gateway.masking.MaskedValue;
import com.whosly.gateway.masking.MaskingException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The type table of MySQL's binary protocol: how wide each type is on the wire, and
 * how a masked value is written back for it.
 *
 * <p>One table serves both directions on purpose. Parsing a binary row means walking
 * columns to find where each value ends, and writing one means producing those bytes
 * again; if the two disagreed about a type's layout, a row would be reassembled with
 * values shifted into the wrong columns — which is why they must never be two tables.</p>
 *
 * <p>Types whose binary layout is not reproduced here are reported as <em>unknown</em>
 * rather than guessed: a wrong width does not fail loudly, it silently mis-aligns every
 * following column. The row parser treats an unknown type as "this result set cannot be
 * masked", so the uncertainty stays fail-closed.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class MySQLBinaryValues {

    /** Fixed-width numeric types, keyed by the name the column metadata reports. */
    private static final Map<String, Integer> FIXED_WIDTH_TYPES = Map.of(
            "tinyint", 1,
            "smallint", 2,
            "int", 4,
            "mediumint", 4,
            "bigint", 8,
            "year", 2);

    /** Floating point types, sent as IEEE-754 little-endian. */
    private static final Map<String, Integer> FLOATING_POINT_TYPES = Map.of(
            "float", 4,
            "double", 8);

    /**
     * Types carried as a length-encoded string.
     *
     * <p>{@code decimal} is here because a packed-decimal value is length-prefixed like
     * a string; its contents are not reproducible, so it can be read but not rewritten.
     * {@code bit} is length-prefixed like a blob and <em>can</em> be rewritten as raw
     * length-encoded bytes. The row parser still verifies that it consumed the payload
     * exactly, which is what keeps this reading of the layout checkable rather than
     * assumed.</p>
     */
    private static final Set<String> LENGTH_ENCODED_TYPES = Set.of(
            "varchar", "varbinary_or_varchar", "binary_or_char", "tinyblob", "mediumblob",
            "longblob", "blob", "json", "enum", "set", "decimal", "bit");

    /** Types prefixed by one byte giving the length of what follows. */
    private static final Set<String> LENGTH_BYTE_PREFIXED_TYPES = Set.of(
            "date", "datetime", "timestamp", "time");

    private MySQLBinaryValues() {
    }

    /**
     * Length of one value in a binary row.
     *
     * @param typeName name reported by the column metadata
     * @param payload  buffer holding the row
     * @param offset   where the value starts
     * @param end      end of the payload
     * @return the value's length including any prefix, or empty when the type's layout
     *         is not known or the value does not fit the payload
     */
    static Optional<Integer> valueLength(String typeName, byte[] payload, int offset, int end) {
        Integer fixed = FIXED_WIDTH_TYPES.get(typeName);
        if (fixed != null) {
            return offset + fixed <= end ? Optional.of(fixed) : Optional.empty();
        }
        Integer floatingPoint = FLOATING_POINT_TYPES.get(typeName);
        if (floatingPoint != null) {
            return offset + floatingPoint <= end ? Optional.of(floatingPoint) : Optional.empty();
        }
        if (LENGTH_ENCODED_TYPES.contains(typeName)) {
            return MySQLTextRow.readLengthEncodedLength(payload, offset, end)
                    .map(prefix -> {
                        int length = prefix.length() + (int) prefix.value();
                        return offset + length <= end ? Optional.of(length) : Optional.<Integer>empty();
                    })
                    .orElseGet(Optional::empty);
        }
        if (LENGTH_BYTE_PREFIXED_TYPES.contains(typeName)) {
            if (offset >= end) {
                return Optional.empty();
            }
            int length = 1 + (payload[offset] & 0xFF);
            return offset + length <= end ? Optional.of(length) : Optional.empty();
        }
        return Optional.empty();
    }

    /** True when this value row's column types are all understood. */
    static boolean isKnownType(String typeName) {
        return FIXED_WIDTH_TYPES.containsKey(typeName)
                || FLOATING_POINT_TYPES.containsKey(typeName)
                || LENGTH_ENCODED_TYPES.contains(typeName)
                || LENGTH_BYTE_PREFIXED_TYPES.contains(typeName);
    }

    /**
     * Wire bytes for a non-null masked value, prefix included.
     *
     * @throws MaskingException         when this type's binary layout is not reproduced
     * @throws IllegalArgumentException when called with a NULL value, which is written by
     *                                  setting the column's null bitmap bit instead
     */
    public static byte[] encode(ColumnMetadata column, MaskedValue value) {
        Objects.requireNonNull(column, "column must not be null");
        Objects.requireNonNull(value, "value must not be null");
        if (value.isNull()) {
            throw new IllegalArgumentException("A NULL value is written through the null bitmap, not encoded: "
                    + column.name());
        }

        String typeName = column.typeName();
        if (LENGTH_ENCODED_TYPES.contains(typeName)) {
            if ("decimal".equals(typeName)) {
                throw new MaskingException("Packed decimal values cannot be rewritten: column " + column.name());
            }
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            byte[] masked = value.bytes();
            MySQLTextRow.writeLength(encoded, masked.length);
            encoded.writeBytes(masked);
            return encoded.toByteArray();
        }
        Integer width = FIXED_WIDTH_TYPES.get(typeName);
        if (width != null) {
            return encodeInteger(typeName, width, value.bytes(), column.name());
        }
        Integer floatingPoint = FLOATING_POINT_TYPES.get(typeName);
        if (floatingPoint != null) {
            return encodeFloatingPoint(floatingPoint, value.bytes(), column.name());
        }
        throw new MaskingException("Binary values of type " + typeName
                + " cannot carry a non-null masked value: column " + column.name());
    }

    private static byte[] encodeInteger(String typeName, int width, byte[] masked, String columnName) {
        long parsed;
        try {
            parsed = Long.parseLong(new String(masked, StandardCharsets.US_ASCII).trim());
        } catch (NumberFormatException e) {
            throw new MaskingException("Masked value is not an integer, so it cannot be written to the "
                    + typeName + " column " + columnName);
        }
        /*
         * The column's signedness is not carried through the metadata, so a value that
         * only fits unsigned is refused: writing it as its two's complement would change
         * the number the client reads.
         */
        if (width < 8) {
            long limit = 1L << (width * 8 - 1);
            if (parsed < -limit || parsed > limit - 1) {
                throw new MaskingException("Masked value " + parsed + " does not fit a signed " + typeName
                        + " column " + columnName);
            }
        }
        return littleEndian(parsed, width);
    }

    private static byte[] encodeFloatingPoint(int width, byte[] masked, String columnName) {
        double parsed;
        try {
            parsed = Double.parseDouble(new String(masked, StandardCharsets.US_ASCII).trim());
        } catch (NumberFormatException e) {
            throw new MaskingException("Masked value is not a number, so it cannot be written to column "
                    + columnName);
        }
        if (width == 4) {
            return littleEndian(Float.floatToIntBits((float) parsed), width);
        }
        return littleEndian(Double.doubleToLongBits(parsed), width);
    }

    private static byte[] littleEndian(long value, int width) {
        byte[] bytes = new byte[width];
        for (int index = 0; index < width; index++) {
            bytes[index] = (byte) ((value >> (8 * index)) & 0xFF);
        }
        return bytes;
    }
}
