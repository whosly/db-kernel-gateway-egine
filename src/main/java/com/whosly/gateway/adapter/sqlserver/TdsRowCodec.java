package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.masking.MaskedValue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Parse / re-encode TDS {@code ROW} ({@code 0xD1}) values for known layouts.
 *
 * <p>Fail-closed: PLP, TEXTPTR, NBCROW, and unknown layouts return empty so the
 * masking interceptor can deny rather than mis-attribute values.</p>
 */
public final class TdsRowCodec {

    public static final int PLP_NULL_LO = 0xFFFFFFFF;
    public static final int PLP_NULL_HI = 0xFFFFFFFF;

    private TdsRowCodec() {
    }

    /**
     * Parses one ROW token starting at {@code offset} (must point at {@code 0xD1}).
     *
     * @return empty when the token is truncated or a column layout is unsupported
     */
    public static Optional<ParsedRow> tryParse(byte[] payload, int offset, List<TdsColumnDefinition> columns) {
        if (payload == null || columns == null || offset < 0 || offset >= payload.length) {
            return Optional.empty();
        }
        if ((payload[offset] & 0xFF) != TdsTokenType.ROW.getCode()) {
            return Optional.empty();
        }
        int cursor = offset + 1;
        List<MaskedValue> values = new ArrayList<>(columns.size());
        for (TdsColumnDefinition column : columns) {
            Optional<ValueParse> parsed = readValue(payload, cursor, column);
            if (parsed.isEmpty()) {
                return Optional.empty();
            }
            values.add(parsed.get().value());
            cursor = parsed.get().nextOffset();
        }
        return Optional.of(new ParsedRow(List.copyOf(values), cursor - offset));
    }

    /**
     * Encodes a ROW token from already-masked values matching {@code columns} order.
     *
     * @throws IllegalArgumentException when a value cannot be represented
     */
    public static byte[] encode(List<TdsColumnDefinition> columns, List<MaskedValue> values) {
        if (columns.size() != values.size()) {
            throw new IllegalArgumentException("column/value count mismatch");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.ROW.getCode());
        for (int i = 0; i < columns.size(); i++) {
            writeValue(out, columns.get(i), values.get(i));
        }
        return out.toByteArray();
    }

    private static Optional<ValueParse> readValue(byte[] payload, int offset, TdsColumnDefinition column) {
        if (column.plp()) {
            return readPlp(payload, offset);
        }
        return switch (column.type().layout()) {
            case FIXED -> readFixed(payload, offset, column);
            case BYTE_LEN -> readByteLen(payload, offset, column);
            case USHORT_LEN -> readUshortLen(payload, offset, column);
            case TEXTPTR, UNSUPPORTED -> Optional.empty();
        };
    }

    private static Optional<ValueParse> readFixed(byte[] payload, int offset, TdsColumnDefinition column) {
        int len = column.fixedByteLength();
        if (len <= 0 || offset + len > payload.length) {
            return Optional.empty();
        }
        byte[] bytes = new byte[len];
        System.arraycopy(payload, offset, bytes, 0, len);
        return Optional.of(new ValueParse(MaskedValue.of(bytes), offset + len));
    }

    private static Optional<ValueParse> readByteLen(byte[] payload, int offset, TdsColumnDefinition column) {
        if (offset >= payload.length) {
            return Optional.empty();
        }
        int len = payload[offset] & 0xFF;
        int dataStart = offset + 1;
        if (len == 0) {
            return Optional.of(new ValueParse(MaskedValue.ofNull(), dataStart));
        }
        if (dataStart + len > payload.length) {
            return Optional.empty();
        }
        byte[] bytes = new byte[len];
        System.arraycopy(payload, dataStart, bytes, 0, len);
        return Optional.of(new ValueParse(MaskedValue.of(bytes), dataStart + len));
    }

    private static Optional<ValueParse> readUshortLen(byte[] payload, int offset, TdsColumnDefinition column) {
        if (offset + 2 > payload.length) {
            return Optional.empty();
        }
        int len = TdsColMetadata.u16le(payload, offset);
        int dataStart = offset + 2;
        if (len == 0xFFFF) {
            return Optional.of(new ValueParse(MaskedValue.ofNull(), dataStart));
        }
        if (dataStart + len > payload.length) {
            return Optional.empty();
        }
        byte[] bytes = new byte[len];
        System.arraycopy(payload, dataStart, bytes, 0, len);
        return Optional.of(new ValueParse(MaskedValue.of(bytes), dataStart + len));
    }

    private static Optional<ValueParse> readPlp(byte[] payload, int offset) {
        if (offset + 8 > payload.length) {
            return Optional.empty();
        }
        long total = u64le(payload, offset);
        int cursor = offset + 8;
        if (total == 0xFFFFFFFFFFFFFFFFL) {
            return Optional.of(new ValueParse(MaskedValue.ofNull(), cursor));
        }
        // Chunked PLP — skip chunks without materializing (masking refuses non-null).
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        while (true) {
            if (cursor + 4 > payload.length) {
                return Optional.empty();
            }
            int chunkLen = TdsColMetadata.u32le(payload, cursor);
            cursor += 4;
            if (chunkLen == 0) {
                break;
            }
            if (cursor + chunkLen > payload.length) {
                return Optional.empty();
            }
            sink.write(payload, cursor, chunkLen);
            cursor += chunkLen;
        }
        return Optional.of(new ValueParse(MaskedValue.of(sink.toByteArray()), cursor));
    }

    private static void writeValue(ByteArrayOutputStream out, TdsColumnDefinition column, MaskedValue value) {
        if (column.plp()) {
            writePlp(out, value);
            return;
        }
        switch (column.type().layout()) {
            case FIXED -> writeFixed(out, column, value);
            case BYTE_LEN -> writeByteLen(out, column, value);
            case USHORT_LEN -> writeUshortLen(out, column, value);
            default -> throw new IllegalArgumentException("unsupported layout for encode: " + column.type());
        }
    }

    private static void writeFixed(ByteArrayOutputStream out, TdsColumnDefinition column, MaskedValue value) {
        if (value.isNull()) {
            throw new IllegalArgumentException("FIXED type cannot encode NULL: " + column.name());
        }
        byte[] bytes = encodeNonNullBytes(column, value);
        if (bytes.length != column.fixedByteLength()) {
            throw new IllegalArgumentException("FIXED length mismatch for " + column.name()
                    + ": expected " + column.fixedByteLength() + " got " + bytes.length);
        }
        out.write(bytes, 0, bytes.length);
    }

    private static void writeByteLen(ByteArrayOutputStream out, TdsColumnDefinition column, MaskedValue value) {
        if (value.isNull()) {
            out.write(0);
            return;
        }
        byte[] bytes = encodeNonNullBytes(column, value);
        if (bytes.length > 255) {
            throw new IllegalArgumentException("BYTE_LEN value too large: " + bytes.length);
        }
        out.write(bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeUshortLen(ByteArrayOutputStream out, TdsColumnDefinition column, MaskedValue value) {
        if (value.isNull()) {
            out.write(0xFF);
            out.write(0xFF);
            return;
        }
        byte[] bytes = encodeNonNullBytes(column, value);
        if (bytes.length > 0xFFFE) {
            throw new IllegalArgumentException("USHORT_LEN value too large: " + bytes.length);
        }
        out.write(bytes.length & 0xFF);
        out.write((bytes.length >> 8) & 0xFF);
        out.write(bytes, 0, bytes.length);
    }

    private static void writePlp(ByteArrayOutputStream out, MaskedValue value) {
        if (!value.isNull()) {
            throw new IllegalArgumentException("non-null PLP rewrite is not supported");
        }
        // PLP_NULL = 0xFFFFFFFFFFFFFFFF
        for (int i = 0; i < 8; i++) {
            out.write(0xFF);
        }
    }

    /**
     * Converts a rule output into TDS value bytes for text / uuid columns.
     * Numeric/temporal FIXED/BYTE_LEN keep raw bytes when already binary.
     */
    static byte[] encodeNonNullBytes(TdsColumnDefinition column, MaskedValue value) {
        if (value.isNull()) {
            throw new IllegalArgumentException("null value");
        }
        TdsTypeCode type = column.type();
        if (type.isUnicodeText()) {
            String text = value.asText(StandardCharsets.UTF_8);
            return text.getBytes(StandardCharsets.UTF_16LE);
        }
        if (type == TdsTypeCode.BIGVARCHR || type == TdsTypeCode.BIGCHAR) {
            // Collation-aware code pages are not terminated here; treat as UTF-8 bytes
            // of the masked text (ASCII-safe masks / encrypt hex stay valid).
            return value.asText(StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
        }
        if (type == TdsTypeCode.GUIDTYPE) {
            return encodeGuid(value);
        }
        // Already binary (INTN etc.) — pass through when lengths match.
        return value.bytes();
    }

    private static byte[] encodeGuid(MaskedValue value) {
        if (value.bytes().length == 16) {
            return value.bytes();
        }
        String text = value.asText(StandardCharsets.UTF_8).trim();
        UUID uuid = UUID.fromString(text);
        ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        // SQL Server uniqueidentifier: first 3 fields little-endian, last 8 big-endian bytes.
        buffer.putInt((int) (uuid.getMostSignificantBits() >> 32));
        buffer.putShort((short) (uuid.getMostSignificantBits() >> 16));
        buffer.putShort((short) uuid.getMostSignificantBits());
        ByteBuffer be = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        be.putLong(uuid.getLeastSignificantBits());
        buffer.put(be.array());
        return buffer.array();
    }

    private static long u64le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24)
                | ((bytes[offset + 4] & 0xFFL) << 32)
                | ((bytes[offset + 5] & 0xFFL) << 40)
                | ((bytes[offset + 6] & 0xFFL) << 48)
                | ((bytes[offset + 7] & 0xFFL) << 56);
    }

    public record ParsedRow(List<MaskedValue> values, int wireLength) {
    }

    private record ValueParse(MaskedValue value, int nextOffset) {
    }
}
