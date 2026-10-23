package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.masking.ColumnMetadata;
import com.whosly.gateway.masking.MaskedValue;
import com.whosly.gateway.masking.MaskingException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Wire representation of a masked value in PostgreSQL's binary format.
 *
 * <p>Binary format is chosen per column by the client, and a masked value can only be
 * written if its bytes are valid for the column's type — a client parses the value
 * with the type it asked for, so bytes that are not a valid encoding of that type are
 * a corrupted result set, not a masked one. This class therefore encodes only the
 * types it can reproduce exactly and refuses the rest (rule 8.2).</p>
 *
 * <p>Supported non-null replacements:</p>
 * <ul>
 *   <li><b>NULL</b> is written as the protocol's own NULL marker and is valid for every
 *       type, so nulling a binary column always works;</li>
 *   <li><b>text-family types</b> — and {@code bytea} — have a binary representation that
 *       is simply the value's bytes, so a masked value carries over unchanged;</li>
 *   <li><b>fixed-width numeric / boolean</b> ({@code int2}/{@code int4}/{@code int8},
 *       {@code float4}/{@code float8}, {@code bool}) are rewritten as big-endian IEEE /
 *       two's complement values parsed from the masked text;</li>
 *   <li><b>{@code numeric}</b> as PostgreSQL's base-10000 digit layout;</li>
 *   <li><b>{@code date}/{@code time}/{@code timestamp}/{@code timestamptz}</b> as days or
 *       microseconds since the PostgreSQL epoch (2000-01-01);</li>
 *   <li><b>{@code uuid}</b> as 16 big-endian bytes.</li>
 * </ul>
 *
 * <p>{@code interval}, {@code money}, {@code timetz} and unknown types still refuse a
 * non-null replacement.</p>
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

    private static final int NUMERIC_POS = 0x0000;
    private static final int NUMERIC_NEG = 0x4000;
    private static final int NUMERIC_NAN = 0xC000;

    /** PostgreSQL epoch: 2000-01-01 00:00:00 UTC. */
    private static final LocalDate PG_EPOCH_DATE = LocalDate.of(2000, 1, 1);
    private static final LocalDateTime PG_EPOCH_TIMESTAMP = LocalDateTime.of(2000, 1, 1, 0, 0, 0);

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
        if ("numeric".equals(typeName)) {
            return encodeNumeric(value.bytes(), column.name());
        }
        if ("date".equals(typeName)) {
            return encodeDate(value.bytes(), column.name());
        }
        if ("time".equals(typeName)) {
            return encodeTime(value.bytes(), column.name());
        }
        if ("timestamp".equals(typeName)) {
            return encodeTimestamp(value.bytes(), column.name(), false);
        }
        if ("timestamptz".equals(typeName)) {
            return encodeTimestamp(value.bytes(), column.name(), true);
        }
        if ("uuid".equals(typeName)) {
            return encodeUuid(value.bytes(), column.name());
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

    private static byte[] encodeNumeric(byte[] masked, String columnName) {
        String text = new String(masked, StandardCharsets.US_ASCII).trim();
        if (text.isEmpty()) {
            throw new MaskingException("Masked value is not a numeric literal for column " + columnName);
        }
        if ("NaN".equalsIgnoreCase(text)) {
            ByteBuffer buffer = ByteBuffer.allocate(8);
            buffer.putShort((short) 0);
            buffer.putShort((short) 0);
            buffer.putShort((short) NUMERIC_NAN);
            buffer.putShort((short) 0);
            return buffer.array();
        }
        BigDecimal value;
        try {
            value = new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new MaskingException("Masked value is not a numeric literal for column " + columnName);
        }
        return encodeNumeric(value);
    }

    /**
     * Packs a {@link BigDecimal} into PostgreSQL {@code numeric} binary form
     * (ndigits / weight / sign / dscale + base-10000 digits).
     */
    static byte[] encodeNumeric(BigDecimal value) {
        int sign = value.signum() < 0 ? NUMERIC_NEG : NUMERIC_POS;
        int dscale = Math.max(value.scale(), 0);
        if (value.signum() == 0) {
            ByteBuffer buffer = ByteBuffer.allocate(8);
            buffer.putShort((short) 0);
            buffer.putShort((short) 0);
            buffer.putShort((short) NUMERIC_POS);
            buffer.putShort((short) dscale);
            return buffer.array();
        }

        BigDecimal normalized = value.abs().setScale(dscale, RoundingMode.UNNECESSARY);
        String unscaled = normalized.unscaledValue().toString();
        int fracPad = (4 - (dscale % 4)) % 4;
        int intLen = unscaled.length() - dscale;
        if (intLen < 0) {
            unscaled = "0".repeat(-intLen) + unscaled;
            intLen = 0;
        }
        int intPad = (4 - (intLen % 4)) % 4;
        String padded = "0".repeat(intPad) + unscaled + "0".repeat(fracPad);
        int groups = padded.length() / 4;
        int weight = (intLen + intPad) / 4 - 1;

        List<Short> digits = new ArrayList<>(groups);
        for (int index = 0; index < groups; index++) {
            int start = index * 4;
            digits.add(Short.parseShort(padded.substring(start, start + 4)));
        }
        while (!digits.isEmpty() && digits.get(0) == 0) {
            digits.remove(0);
            weight--;
        }
        while (!digits.isEmpty() && digits.get(digits.size() - 1) == 0) {
            digits.remove(digits.size() - 1);
        }
        ByteBuffer buffer = ByteBuffer.allocate(8 + digits.size() * 2);
        buffer.putShort((short) digits.size());
        buffer.putShort((short) weight);
        buffer.putShort((short) sign);
        buffer.putShort((short) dscale);
        for (short digit : digits) {
            buffer.putShort(digit);
        }
        return buffer.array();
    }

    private static byte[] encodeDate(byte[] masked, String columnName) {
        String text = new String(masked, StandardCharsets.US_ASCII).trim();
        try {
            LocalDate date = LocalDate.parse(text);
            int days = (int) (date.toEpochDay() - PG_EPOCH_DATE.toEpochDay());
            return bigEndian(days, 4);
        } catch (DateTimeParseException e) {
            throw new MaskingException("Masked value is not a date for column " + columnName);
        }
    }

    private static byte[] encodeTime(byte[] masked, String columnName) {
        String text = new String(masked, StandardCharsets.US_ASCII).trim();
        try {
            LocalTime time = LocalTime.parse(text);
            long micros = time.toNanoOfDay() / 1_000L;
            return bigEndian(micros, 8);
        } catch (DateTimeParseException e) {
            throw new MaskingException("Masked value is not a time for column " + columnName);
        }
    }

    private static byte[] encodeTimestamp(byte[] masked, String columnName, boolean withTimeZone) {
        String text = new String(masked, StandardCharsets.US_ASCII).trim().replace(' ', 'T');
        try {
            long micros;
            if (withTimeZone) {
                OffsetDateTime odt;
                try {
                    odt = OffsetDateTime.parse(text);
                } catch (DateTimeParseException ignored) {
                    // Accept bare local form as UTC for masking convenience.
                    odt = LocalDateTime.parse(stripTrailingZ(text)).atOffset(ZoneOffset.UTC);
                }
                long epochMicros = odt.toInstant().toEpochMilli() * 1000L
                        + Math.floorMod(odt.getNano(), 1_000_000) / 1000;
                long pgEpochMicros = OffsetDateTime.of(PG_EPOCH_TIMESTAMP, ZoneOffset.UTC)
                        .toInstant().toEpochMilli() * 1000L;
                micros = epochMicros - pgEpochMicros;
            } else {
                LocalDateTime ldt = LocalDateTime.parse(stripTrailingZ(text));
                micros = java.time.Duration.between(PG_EPOCH_TIMESTAMP, ldt).toNanos() / 1_000L;
            }
            return bigEndian(micros, 8);
        } catch (DateTimeParseException | ArithmeticException e) {
            throw new MaskingException("Masked value is not a timestamp for column " + columnName);
        }
    }

    private static String stripTrailingZ(String text) {
        if (text.endsWith("Z") || text.endsWith("z")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static byte[] encodeUuid(byte[] masked, String columnName) {
        String text = new String(masked, StandardCharsets.US_ASCII).trim();
        try {
            UUID uuid = UUID.fromString(text);
            ByteBuffer buffer = ByteBuffer.allocate(16);
            buffer.putLong(uuid.getMostSignificantBits());
            buffer.putLong(uuid.getLeastSignificantBits());
            return buffer.array();
        } catch (IllegalArgumentException e) {
            throw new MaskingException("Masked value is not a uuid for column " + columnName);
        }
    }

    private static byte[] bigEndian(long value, int width) {
        byte[] bytes = new byte[width];
        for (int index = 0; index < width; index++) {
            bytes[width - 1 - index] = (byte) ((value >> (8 * index)) & 0xFF);
        }
        return bytes;
    }
}
