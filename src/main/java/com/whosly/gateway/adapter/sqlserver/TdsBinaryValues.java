package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.masking.MaskedValue;
import com.whosly.gateway.masking.MaskingException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encodes / decodes TDS numeric and temporal TYPE_VARBYTE payloads for masking.
 *
 * <p>Rules emit UTF-8 text constants ({@code 0}, {@code 1970-01-01}, …). This class
 * turns those strings into the little-endian layouts MS-TDS defines, and the reverse
 * for {@code toRuleValue}. MONEY* is scale-4 integer; XML/UDT non-null (BINXML/CLR) and
 * unknown layouts refuse rather than guess (fail-closed).</p>
 */
public final class TdsBinaryValues {

    private static final LocalDate DATE_EPOCH = LocalDate.of(1, 1, 1);
    private static final LocalDate DATETIME_EPOCH = LocalDate.of(1900, 1, 1);

    private static final Pattern DATE_PATTERN =
            Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
    private static final Pattern TIME_PATTERN =
            Pattern.compile("^(\\d{1,2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,7}))?$");
    private static final Pattern DATETIME_PATTERN = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})(?:[ T](\\d{1,2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,7}))?)?$");

    private TdsBinaryValues() {
    }

    /**
     * Wire bytes for a non-null masked value (no length prefix — caller adds BYTE_LEN /
     * USHORT_LEN / FIXED / PLP framing).
     */
    public static byte[] encode(TdsColumnDefinition column, MaskedValue value) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(value, "value");
        if (value.isNull()) {
            throw new IllegalArgumentException("NULL is framed by the row codec, not encoded: "
                    + column.name());
        }
        TdsTypeCode type = column.type();
        String text = value.asText(StandardCharsets.UTF_8).trim();
        return switch (type) {
            case INT1TYPE -> encodeInt(text, 1, column.name());
            case INT2TYPE -> encodeInt(text, 2, column.name());
            case INT4TYPE -> encodeInt(text, 4, column.name());
            case INT8TYPE -> encodeInt(text, 8, column.name());
            case INTN -> encodeInt(text, column.maxLength() > 0 ? column.maxLength() : 4, column.name());
            case FLT4TYPE -> encodeFloat(text, 4, column.name());
            case FLT8TYPE -> encodeFloat(text, 8, column.name());
            case FLOATN -> encodeFloat(text, column.maxLength() > 0 ? column.maxLength() : 8, column.name());
            case BITTYPE, BITN -> encodeBit(text, column.name());
            case DECIMALN, NUMERICN -> encodeDecimal(text, column.precision(), column.scale(), column.name());
            case DATEN -> encodeDate(text, column.name());
            case TIMEN -> encodeTime(text, column.scale(), column.name());
            case DATETIME2N -> encodeDateTime2(text, column.scale(), column.name());
            case DATETIMEOFFSETN -> encodeDateTimeOffset(text, column.scale(), column.name());
            case DATETIMETYPE -> encodeDateTime(text, false, column.name());
            case DATETIM4TYPE -> encodeDateTime(text, true, column.name());
            case DATETIMN -> encodeDateTime(text, column.maxLength() == 4, column.name());
            case MONEYTYPE -> encodeMoney(text, 8, column.name());
            case MONEY4TYPE -> encodeMoney(text, 4, column.name());
            case MONEYN -> encodeMoney(text, column.maxLength() == 4 ? 4 : 8, column.name());
            default -> throw new MaskingException("TDS type " + type.typeName()
                    + " cannot carry a non-null masked value: column " + column.name());
        };
    }

    /** UTF-8 text form of a non-null wire value for masking rules. */
    public static String decodeToText(TdsColumnDefinition column, byte[] wire) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(wire, "wire");
        TdsTypeCode type = column.type();
        return switch (type) {
            case INT1TYPE, INT2TYPE, INT4TYPE, INT8TYPE, INTN -> Long.toString(readSignedLe(wire, wire.length));
            case FLT4TYPE -> Float.toString(ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN).getFloat());
            case FLT8TYPE -> Double.toString(ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN).getDouble());
            case FLOATN -> {
                if (wire.length == 4) {
                    yield Float.toString(ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN).getFloat());
                }
                if (wire.length == 8) {
                    yield Double.toString(ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN).getDouble());
                }
                throw new MaskingException("floatn wire length mismatch");
            }
            case BITTYPE, BITN -> (wire.length > 0 && wire[0] != 0) ? "1" : "0";
            case DECIMALN, NUMERICN -> decodeDecimal(wire, column.scale());
            case DATEN -> decodeDate(wire);
            case TIMEN -> decodeTime(wire, column.scale());
            case DATETIME2N -> decodeDateTime2(wire, column.scale());
            case DATETIMEOFFSETN -> decodeDateTimeOffset(wire, column.scale());
            case DATETIMETYPE, DATETIM4TYPE, DATETIMN -> decodeLegacyDateTime(wire);
            case MONEYTYPE, MONEY4TYPE, MONEYN -> decodeMoney(wire);
            default -> throw new MaskingException("cannot decode TDS type " + type.typeName()
                    + " for column " + column.name());
        };
    }

    public static boolean isEncodable(TdsTypeCode type) {
        return switch (type) {
            case INT1TYPE, INT2TYPE, INT4TYPE, INT8TYPE, INTN,
                 FLT4TYPE, FLT8TYPE, FLOATN,
                 BITTYPE, BITN,
                 DECIMALN, NUMERICN,
                 MONEYTYPE, MONEY4TYPE, MONEYN,
                 DATEN, TIMEN, DATETIME2N, DATETIMEOFFSETN,
                 DATETIMETYPE, DATETIM4TYPE, DATETIMN -> true;
            default -> false;
        };
    }

    static int decimalIntegerWidth(int precision) {
        if (precision < 1 || precision > 38) {
            throw new MaskingException("decimal precision out of range: " + precision);
        }
        if (precision <= 9) {
            return 4;
        }
        if (precision <= 19) {
            return 8;
        }
        if (precision <= 28) {
            return 12;
        }
        return 16;
    }

    static int timeByteLength(int scale) {
        if (scale < 0 || scale > 7) {
            throw new MaskingException("time scale out of range: " + scale);
        }
        if (scale <= 2) {
            return 3;
        }
        if (scale <= 4) {
            return 4;
        }
        return 5;
    }

    private static byte[] encodeInt(String text, int width, String columnName) {
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new MaskingException("Masked value is not an integer for column " + columnName);
        }
        return writeSignedLe(value, width);
    }

    private static byte[] encodeFloat(String text, int width, String columnName) {
        try {
            if (width == 4) {
                float f = Float.parseFloat(text);
                ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
                buffer.putFloat(f);
                return buffer.array();
            }
            if (width == 8) {
                double d = Double.parseDouble(text);
                ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                buffer.putDouble(d);
                return buffer.array();
            }
        } catch (NumberFormatException e) {
            throw new MaskingException("Masked value is not a floating number for column " + columnName);
        }
        throw new MaskingException("Unsupported float width " + width + " for column " + columnName);
    }

    private static byte[] encodeBit(String text, String columnName) {
        String normalized = text.toLowerCase(Locale.ROOT);
        if ("1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized)) {
            return new byte[]{1};
        }
        if ("0".equals(normalized) || "false".equals(normalized) || "no".equals(normalized)) {
            return new byte[]{0};
        }
        throw new MaskingException("Masked value is not a bit for column " + columnName);
    }

    private static byte[] encodeDecimal(String text, int precision, int scale, String columnName) {
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new MaskingException("Masked value is not a decimal for column " + columnName);
        }
        if (scale < 0 || scale > 38) {
            throw new MaskingException("decimal scale out of range for column " + columnName);
        }
        BigDecimal scaled = decimal.setScale(scale, RoundingMode.UNNECESSARY);
        BigInteger unscaled = scaled.unscaledValue().abs();
        int width = decimalIntegerWidth(precision <= 0 ? 18 : precision);
        // Ensure unscaled fits; pad to width.
        byte[] magnitude = unscaled.toByteArray(); // big-endian two's complement
        if (magnitude.length > width + 1 || (magnitude.length == width + 1 && magnitude[0] != 0)) {
            throw new MaskingException("Masked decimal does not fit precision for column " + columnName);
        }
        byte[] le = new byte[width];
        // Convert big-endian magnitude to little-endian fixed width (unsigned magnitude).
        int copy = Math.min(magnitude.length, width);
        for (int i = 0; i < copy; i++) {
            le[i] = magnitude[magnitude.length - 1 - i];
        }
        byte[] out = new byte[1 + width];
        out[0] = (byte) (scaled.signum() < 0 ? 0 : 1);
        System.arraycopy(le, 0, out, 1, width);
        return out;
    }

    private static String decodeDecimal(byte[] wire, int scale) {
        if (wire.length < 5) {
            throw new MaskingException("decimal wire too short");
        }
        boolean negative = wire[0] == 0;
        // magnitude little-endian → big-endian for BigInteger
        int magLen = wire.length - 1;
        byte[] magBe = new byte[magLen];
        for (int i = 0; i < magLen; i++) {
            magBe[i] = wire[wire.length - 1 - i];
        }
        BigInteger unscaled = new BigInteger(1, magBe);
        if (negative) {
            unscaled = unscaled.negate();
        }
        return new BigDecimal(unscaled, scale).toPlainString();
    }


    private static byte[] encodeMoney(String text, int width, String columnName) {
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new MaskingException("Masked value is not money for column " + columnName);
        }
        BigDecimal scaled = decimal.movePointRight(4).setScale(0, RoundingMode.HALF_UP);
        BigInteger unscaled = scaled.toBigIntegerExact();
        if (width == 4) {
            if (unscaled.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                    || unscaled.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new MaskingException("smallmoney out of range for column " + columnName);
            }
            return writeSignedLe(unscaled.intValue(), 4);
        }
        if (width == 8) {
            if (unscaled.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0
                    || unscaled.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                throw new MaskingException("money out of range for column " + columnName);
            }
            return writeSignedLe(unscaled.longValue(), 8);
        }
        throw new MaskingException("Unsupported money width " + width + " for column " + columnName);
    }

    private static String decodeMoney(byte[] wire) {
        long ticks = readSignedLe(wire, wire.length);
        return BigDecimal.valueOf(ticks, 4).toPlainString();
    }

    private static byte[] encodeDate(String text, String columnName) {
        Matcher matcher = DATE_PATTERN.matcher(text);
        if (!matcher.matches()) {
            throw new MaskingException("Masked value is not a date for column " + columnName);
        }
        LocalDate date = LocalDate.of(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
        long days = ChronoUnit.DAYS.between(DATE_EPOCH, date);
        if (days < 0 || days > 0xFFFFFFL) {
            throw new MaskingException("Date out of TDS range for column " + columnName);
        }
        return new byte[]{
                (byte) (days & 0xFF),
                (byte) ((days >> 8) & 0xFF),
                (byte) ((days >> 16) & 0xFF)
        };
    }

    private static String decodeDate(byte[] wire) {
        if (wire.length != 3) {
            throw new MaskingException("date wire must be 3 bytes");
        }
        int days = (wire[0] & 0xFF) | ((wire[1] & 0xFF) << 8) | ((wire[2] & 0xFF) << 16);
        return DATE_EPOCH.plusDays(days).toString();
    }

    private static byte[] encodeTime(String text, int scale, String columnName) {
        Matcher matcher = TIME_PATTERN.matcher(text);
        if (!matcher.matches()) {
            throw new MaskingException("Masked value is not a time for column " + columnName);
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        int second = Integer.parseInt(matcher.group(3));
        int fraction = parseFraction(matcher.group(4), scale);
        long ticks = (((hour * 60L + minute) * 60L + second) * pow10(scale)) + fraction;
        return writeUnsignedLe(ticks, timeByteLength(scale));
    }

    private static String decodeTime(byte[] wire, int scale) {
        long ticks = readUnsignedLe(wire, wire.length);
        long perSecond = pow10(scale);
        long totalSeconds = ticks / perSecond;
        long fraction = ticks % perSecond;
        long hour = totalSeconds / 3600;
        long minute = (totalSeconds % 3600) / 60;
        long second = totalSeconds % 60;
        if (scale == 0) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d", hour, minute, second);
        }
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%0" + scale + "d", hour, minute, second, fraction);
    }

    private static byte[] encodeDateTime2(String text, int scale, String columnName) {
        LocalDateTime dateTime = parseLocalDateTime(text, columnName);
        byte[] time = encodeTime(
                String.format(Locale.ROOT, "%02d:%02d:%02d%s",
                        dateTime.getHour(), dateTime.getMinute(), dateTime.getSecond(),
                        fractionSuffix(dateTime.getNano(), scale)),
                scale, columnName);
        byte[] date = encodeDate(dateTime.toLocalDate().toString(), columnName);
        byte[] out = new byte[time.length + date.length];
        System.arraycopy(time, 0, out, 0, time.length);
        System.arraycopy(date, 0, out, time.length, date.length);
        return out;
    }

    private static String decodeDateTime2(byte[] wire, int scale) {
        int timeLen = timeByteLength(scale);
        if (wire.length != timeLen + 3) {
            throw new MaskingException("datetime2 wire length mismatch");
        }
        byte[] time = new byte[timeLen];
        byte[] date = new byte[3];
        System.arraycopy(wire, 0, time, 0, timeLen);
        System.arraycopy(wire, timeLen, date, 0, 3);
        return decodeDate(date) + " " + decodeTime(time, scale);
    }

    private static byte[] encodeDateTimeOffset(String text, int scale, String columnName) {
        OffsetDateTime odt;
        try {
            String normalized = text.contains("T") ? text : text.replace(' ', 'T');
            // Allow "YYYY-MM-DDTHH:MM:SS+00:00" and space before offset.
            if (normalized.matches(".*[+-]\\d{2}:\\d{2}$") && !normalized.contains("T")) {
                normalized = text.trim().replaceFirst(" ", "T");
            }
            odt = OffsetDateTime.parse(normalized.replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            // Fallback: local datetime + "+00:00"
            try {
                LocalDateTime ldt = parseLocalDateTime(text, columnName);
                odt = ldt.atOffset(ZoneOffset.UTC);
            } catch (MaskingException ex) {
                throw new MaskingException("Masked value is not a datetimeoffset for column " + columnName);
            }
        }
        // TDS stores datetime2 in the local offset time, plus offset minutes.
        LocalDateTime local = odt.toLocalDateTime();
        byte[] dt2 = encodeDateTime2(
                local.toLocalDate() + " " + String.format(Locale.ROOT, "%02d:%02d:%02d%s",
                        local.getHour(), local.getMinute(), local.getSecond(),
                        fractionSuffix(local.getNano(), scale)),
                scale, columnName);
        int offsetMinutes = odt.getOffset().getTotalSeconds() / 60;
        if (offsetMinutes < -840 || offsetMinutes > 840) {
            throw new MaskingException("datetimeoffset out of range for column " + columnName);
        }
        byte[] out = new byte[dt2.length + 2];
        System.arraycopy(dt2, 0, out, 0, dt2.length);
        out[dt2.length] = (byte) (offsetMinutes & 0xFF);
        out[dt2.length + 1] = (byte) ((offsetMinutes >> 8) & 0xFF);
        return out;
    }

    private static String decodeDateTimeOffset(byte[] wire, int scale) {
        int timeLen = timeByteLength(scale);
        int expected = timeLen + 3 + 2;
        if (wire.length != expected) {
            throw new MaskingException("datetimeoffset wire length mismatch");
        }
        byte[] dt2 = new byte[timeLen + 3];
        System.arraycopy(wire, 0, dt2, 0, dt2.length);
        short offset = (short) ((wire[dt2.length] & 0xFF) | ((wire[dt2.length + 1] & 0xFF) << 8));
        ZoneOffset zone = ZoneOffset.ofTotalSeconds(offset * 60);
        String local = decodeDateTime2(dt2, scale);
        LocalDateTime ldt = parseLocalDateTime(local, "datetimeoffset");
        return ldt.atOffset(zone).toString();
    }

    private static byte[] encodeDateTime(String text, boolean small, String columnName) {
        LocalDateTime dateTime = parseLocalDateTime(text, columnName);
        long days = ChronoUnit.DAYS.between(DATETIME_EPOCH, dateTime.toLocalDate());
        if (small) {
            if (days < 0 || days > 0xFFFF) {
                throw new MaskingException("smalldatetime out of range for column " + columnName);
            }
            int minutes = dateTime.getHour() * 60 + dateTime.getMinute();
            ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putShort((short) days);
            buffer.putShort((short) minutes);
            return buffer.array();
        }
        // datetime: days int4 + time uint4 in 1/300 seconds
        long nanos = dateTime.toLocalTime().toNanoOfDay();
        long threeHundredths = Math.round(nanos / (1_000_000_000.0 / 300.0));
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt((int) days);
        buffer.putInt((int) threeHundredths);
        return buffer.array();
    }

    private static String decodeLegacyDateTime(byte[] wire) {
        ByteBuffer buffer = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);
        if (wire.length == 4) {
            int days = buffer.getShort() & 0xFFFF;
            int minutes = buffer.getShort() & 0xFFFF;
            LocalDate date = DATETIME_EPOCH.plusDays(days);
            LocalTime time = LocalTime.of(minutes / 60, minutes % 60);
            return LocalDateTime.of(date, time).toString().replace('T', ' ');
        }
        if (wire.length == 8) {
            int days = buffer.getInt();
            long threeHundredths = buffer.getInt() & 0xFFFFFFFFL;
            LocalDate date = DATETIME_EPOCH.plusDays(days);
            long nanos = Math.round(threeHundredths * (1_000_000_000.0 / 300.0));
            LocalTime time = LocalTime.ofNanoOfDay(Math.min(nanos, LocalTime.MAX.toNanoOfDay()));
            return LocalDateTime.of(date, time).toString().replace('T', ' ');
        }
        throw new MaskingException("datetime wire length mismatch");
    }

    private static LocalDateTime parseLocalDateTime(String text, String columnName) {
        Matcher matcher = DATETIME_PATTERN.matcher(text.trim());
        if (!matcher.matches()) {
            try {
                return LocalDateTime.parse(text.trim().replace(' ', 'T'));
            } catch (DateTimeParseException e) {
                // date-only
                Matcher dateOnly = DATE_PATTERN.matcher(text.trim());
                if (dateOnly.matches()) {
                    return LocalDate.parse(text.trim()).atStartOfDay();
                }
                throw new MaskingException("Masked value is not a datetime for column " + columnName);
            }
        }
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        int hour = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
        int minute = matcher.group(5) == null ? 0 : Integer.parseInt(matcher.group(5));
        int second = matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6));
        int nano = 0;
        if (matcher.group(7) != null) {
            String frac = matcher.group(7);
            while (frac.length() < 9) {
                frac = frac + "0";
            }
            if (frac.length() > 9) {
                frac = frac.substring(0, 9);
            }
            nano = Integer.parseInt(frac);
        }
        return LocalDateTime.of(year, month, day, hour, minute, second, nano);
    }

    private static int parseFraction(String raw, int scale) {
        if (raw == null || scale == 0) {
            return 0;
        }
        String frac = raw;
        if (frac.length() > scale) {
            frac = frac.substring(0, scale);
        }
        while (frac.length() < scale) {
            frac = frac + "0";
        }
        return Integer.parseInt(frac);
    }

    private static String fractionSuffix(int nano, int scale) {
        if (scale <= 0) {
            return "";
        }
        int truncated = nano / pow10(9 - scale);
        return String.format(Locale.ROOT, ".%0" + scale + "d", truncated);
    }

    private static int pow10(int n) {
        int value = 1;
        for (int i = 0; i < n; i++) {
            value *= 10;
        }
        return value;
    }

    private static byte[] writeSignedLe(long value, int width) {
        byte[] out = new byte[width];
        for (int i = 0; i < width; i++) {
            out[i] = (byte) ((value >> (8 * i)) & 0xFF);
        }
        return out;
    }

    private static byte[] writeUnsignedLe(long value, int width) {
        return writeSignedLe(value, width);
    }

    private static long readSignedLe(byte[] wire, int width) {
        long value = 0;
        for (int i = 0; i < width; i++) {
            value |= ((long) wire[i] & 0xFFL) << (8 * i);
        }
        // sign-extend
        int bits = width * 8;
        if (bits < 64 && (value & (1L << (bits - 1))) != 0) {
            value |= (-1L) << bits;
        }
        return value;
    }

    private static long readUnsignedLe(byte[] wire, int width) {
        long value = 0;
        for (int i = 0; i < width; i++) {
            value |= ((long) wire[i] & 0xFFL) << (8 * i);
        }
        return value;
    }
}
