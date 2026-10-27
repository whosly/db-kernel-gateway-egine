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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * <p>{@code decimal}/{@code NEWDECIMAL} is ASCII text on the wire (not the
     * on-disk packed BCD), so a masked decimal string rewrites like varchar.
     * {@code bit} is length-prefixed like a blob and can be rewritten as raw
     * length-encoded bytes.</p>
     */
    private static final Set<String> LENGTH_ENCODED_TYPES = Set.of(
            "varchar", "varbinary_or_varchar", "binary_or_char", "tinyblob", "mediumblob",
            "longblob", "blob", "json", "enum", "set", "decimal", "bit");

    /** Types prefixed by one byte giving the length of what follows. */
    private static final Set<String> LENGTH_BYTE_PREFIXED_TYPES = Set.of(
            "date", "datetime", "timestamp", "time");

    private static final Pattern DECIMAL_PATTERN =
            Pattern.compile("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)$");

    private static final Pattern DATE_PATTERN =
            Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");

    private static final Pattern DATETIME_PATTERN = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})(?:[ T](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,6}))?)?$");

    private static final Pattern TIME_PATTERN = Pattern.compile(
            "^(-)?(?:(\\d+) )?(\\d{1,3}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,6}))?$");

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
            byte[] masked = value.bytes();
            if ("decimal".equals(typeName)) {
                masked = encodeDecimalText(masked, column.name());
            }
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
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
        if ("date".equals(typeName)) {
            return encodeDate(value.bytes(), column.name());
        }
        if ("datetime".equals(typeName) || "timestamp".equals(typeName)) {
            return encodeDateTime(value.bytes(), column.name());
        }
        if ("time".equals(typeName)) {
            return encodeTime(value.bytes(), column.name());
        }
        throw new MaskingException("Binary values of type " + typeName
                + " cannot carry a non-null masked value: column " + column.name());
    }

    /**
     * NEWDECIMAL on the wire is ASCII (not packed BCD). Reject masks that are not
     * a decimal literal so a text rule cannot push {@code ****} into a numeric column.
     */
    private static byte[] encodeDecimalText(byte[] masked, String columnName) {
        String text = new String(masked, StandardCharsets.US_ASCII).trim();
        if (!DECIMAL_PATTERN.matcher(text).matches()) {
            throw new MaskingException("Masked value is not a decimal literal, so it cannot be written to column "
                    + columnName);
        }
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] encodeDate(byte[] masked, String columnName) {
        String text = new String(masked, StandardCharsets.US_ASCII).trim();
        Matcher matcher = DATE_PATTERN.matcher(text);
        if (!matcher.matches()) {
            throw new MaskingException("Masked value is not a date, so it cannot be written to column "
                    + columnName);
        }
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        if (year == 0 && month == 0 && day == 0) {
            return new byte[]{0};
        }
        return new byte[]{
                4,
                (byte) (year & 0xFF), (byte) ((year >> 8) & 0xFF),
                (byte) month,
                (byte) day
        };
    }

    private static byte[] encodeDateTime(byte[] masked, String columnName) {
        String text = new String(masked, StandardCharsets.US_ASCII).trim();
        Matcher matcher = DATETIME_PATTERN.matcher(text);
        if (!matcher.matches()) {
            throw new MaskingException("Masked value is not a datetime, so it cannot be written to column "
                    + columnName);
        }
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        int hour = groupOrZero(matcher, 4);
        int minute = groupOrZero(matcher, 5);
        int second = groupOrZero(matcher, 6);
        int micro = parseMicros(matcher.group(7));
        if (year == 0 && month == 0 && day == 0 && hour == 0 && minute == 0 && second == 0 && micro == 0) {
            return new byte[]{0};
        }
        if (hour == 0 && minute == 0 && second == 0 && micro == 0) {
            return new byte[]{
                    4,
                    (byte) (year & 0xFF), (byte) ((year >> 8) & 0xFF),
                    (byte) month,
                    (byte) day
            };
        }
        if (micro == 0) {
            return new byte[]{
                    7,
                    (byte) (year & 0xFF), (byte) ((year >> 8) & 0xFF),
                    (byte) month,
                    (byte) day,
                    (byte) hour,
                    (byte) minute,
                    (byte) second
            };
        }
        byte[] encoded = new byte[12];
        encoded[0] = 11;
        encoded[1] = (byte) (year & 0xFF);
        encoded[2] = (byte) ((year >> 8) & 0xFF);
        encoded[3] = (byte) month;
        encoded[4] = (byte) day;
        encoded[5] = (byte) hour;
        encoded[6] = (byte) minute;
        encoded[7] = (byte) second;
        encoded[8] = (byte) (micro & 0xFF);
        encoded[9] = (byte) ((micro >> 8) & 0xFF);
        encoded[10] = (byte) ((micro >> 16) & 0xFF);
        encoded[11] = (byte) ((micro >> 24) & 0xFF);
        return encoded;
    }

    private static byte[] encodeTime(byte[] masked, String columnName) {
        String text = new String(masked, StandardCharsets.US_ASCII).trim();
        Matcher matcher = TIME_PATTERN.matcher(text);
        if (!matcher.matches()) {
            throw new MaskingException("Masked value is not a time, so it cannot be written to column "
                    + columnName);
        }
        boolean negative = matcher.group(1) != null;
        int days = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        int hour = Integer.parseInt(matcher.group(3));
        int minute = Integer.parseInt(matcher.group(4));
        int second = Integer.parseInt(matcher.group(5));
        int micro = parseMicros(matcher.group(6));
        if (!negative && days == 0 && hour == 0 && minute == 0 && second == 0 && micro == 0) {
            return new byte[]{0};
        }
        if (micro == 0) {
            return new byte[]{
                    8,
                    (byte) (negative ? 1 : 0),
                    (byte) (days & 0xFF), (byte) ((days >> 8) & 0xFF),
                    (byte) ((days >> 16) & 0xFF), (byte) ((days >> 24) & 0xFF),
                    (byte) hour,
                    (byte) minute,
                    (byte) second
            };
        }
        byte[] encoded = new byte[13];
        encoded[0] = 12;
        encoded[1] = (byte) (negative ? 1 : 0);
        encoded[2] = (byte) (days & 0xFF);
        encoded[3] = (byte) ((days >> 8) & 0xFF);
        encoded[4] = (byte) ((days >> 16) & 0xFF);
        encoded[5] = (byte) ((days >> 24) & 0xFF);
        encoded[6] = (byte) hour;
        encoded[7] = (byte) minute;
        encoded[8] = (byte) second;
        encoded[9] = (byte) (micro & 0xFF);
        encoded[10] = (byte) ((micro >> 8) & 0xFF);
        encoded[11] = (byte) ((micro >> 16) & 0xFF);
        encoded[12] = (byte) ((micro >> 24) & 0xFF);
        return encoded;
    }

    private static int groupOrZero(Matcher matcher, int group) {
        String value = matcher.group(group);
        return value == null ? 0 : Integer.parseInt(value);
    }

    private static int parseMicros(String fraction) {
        if (fraction == null || fraction.isEmpty()) {
            return 0;
        }
        String padded = (fraction + "000000").substring(0, 6);
        return Integer.parseInt(padded);
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
