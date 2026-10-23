package com.whosly.gateway.adapter.sqlserver;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Minimal TDS COLMETADATA / ROW builders for unit tests. */
final class TdsTestFrames {

    private TdsTestFrames() {
    }

    static byte[] nvarcharColumnMeta(String name, int maxBytes, boolean nullable) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.COLMETADATA.getCode());
        writeU16(out, 1);
        writeU32(out, 0);
        writeU16(out, nullable ? 1 : 0);
        out.write(TdsTypeCode.NVARCHAR.getCode());
        writeU16(out, maxBytes);
        writeRaw(out, new byte[]{0x09, 0x04, 0x00, 0x00, 0x00});
        writeBVarchar(out, name);
        return out.toByteArray();
    }

    static byte[] guidColumnMeta(String name, boolean nullable) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.COLMETADATA.getCode());
        writeU16(out, 1);
        writeU32(out, 0);
        writeU16(out, nullable ? 1 : 0);
        out.write(TdsTypeCode.GUIDTYPE.getCode());
        out.write(16);
        writeBVarchar(out, name);
        return out.toByteArray();
    }

    static byte[] nvarcharRow(String value) {
        byte[] utf16 = value.getBytes(StandardCharsets.UTF_16LE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.ROW.getCode());
        writeU16(out, utf16.length);
        writeRaw(out, utf16);
        return out.toByteArray();
    }

    static byte[] nvarcharNullRow() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.ROW.getCode());
        writeU16(out, 0xFFFF);
        return out.toByteArray();
    }

    static byte[] guidRow(UUID uuid) {
        byte[] bytes = encodeGuid(uuid);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.ROW.getCode());
        out.write(16);
        writeRaw(out, bytes);
        return out.toByteArray();
    }

    static byte[] encodeGuid(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt((int) (uuid.getMostSignificantBits() >> 32));
        buffer.putShort((short) (uuid.getMostSignificantBits() >> 16));
        buffer.putShort((short) uuid.getMostSignificantBits());
        ByteBuffer be = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        be.putLong(uuid.getLeastSignificantBits());
        buffer.put(be.array());
        return buffer.array();
    }

    static byte[] doneFinal() {
        return TdsDoneToken.encode(TdsDoneToken.STATUS_FINAL | TdsDoneToken.STATUS_COUNT, 0xC1, 1L);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            writeRaw(out, part);
        }
        return out.toByteArray();
    }

    private static void writeRaw(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    private static void writeBVarchar(ByteArrayOutputStream out, String value) {
        byte[] utf16 = value.getBytes(StandardCharsets.UTF_16LE);
        out.write(utf16.length / 2);
        writeRaw(out, utf16);
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
