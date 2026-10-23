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

    static byte[] intnColumnMeta(String name, int maxLen, boolean nullable) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.COLMETADATA.getCode());
        writeU16(out, 1);
        writeU32(out, 0);
        writeU16(out, nullable ? 1 : 0);
        out.write(TdsTypeCode.INTN.getCode());
        out.write(maxLen & 0xFF);
        writeBVarchar(out, name);
        return out.toByteArray();
    }

    static byte[] decimalColumnMeta(String name, int precision, int scale, boolean nullable) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.COLMETADATA.getCode());
        writeU16(out, 1);
        writeU32(out, 0);
        writeU16(out, nullable ? 1 : 0);
        out.write(TdsTypeCode.DECIMALN.getCode());
        int width = 1 + TdsBinaryValues.decimalIntegerWidth(precision);
        out.write(width & 0xFF);
        out.write(precision & 0xFF);
        out.write(scale & 0xFF);
        writeBVarchar(out, name);
        return out.toByteArray();
    }

    static byte[] dateColumnMeta(String name, boolean nullable) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.COLMETADATA.getCode());
        writeU16(out, 1);
        writeU32(out, 0);
        writeU16(out, nullable ? 1 : 0);
        out.write(TdsTypeCode.DATEN.getCode());
        writeBVarchar(out, name);
        return out.toByteArray();
    }

    static byte[] nvarcharMaxColumnMeta(String name, boolean nullable) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.COLMETADATA.getCode());
        writeU16(out, 1);
        writeU32(out, 0);
        writeU16(out, nullable ? 1 : 0);
        out.write(TdsTypeCode.NVARCHAR.getCode());
        writeU16(out, TdsColumnDefinition.MAXLENGTH_PLP);
        writeRaw(out, new byte[]{0x09, 0x04, 0x00, 0x00, 0x00});
        writeBVarchar(out, name);
        return out.toByteArray();
    }

    static byte[] twoNvarcharColumnMeta(String left, String right, boolean nullable) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.COLMETADATA.getCode());
        writeU16(out, 2);
        for (String name : new String[]{left, right}) {
            writeU32(out, 0);
            writeU16(out, nullable ? 1 : 0);
            out.write(TdsTypeCode.NVARCHAR.getCode());
            writeU16(out, 100);
            writeRaw(out, new byte[]{0x09, 0x04, 0x00, 0x00, 0x00});
            writeBVarchar(out, name);
        }
        return out.toByteArray();
    }

    static byte[] intnRow(int width, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.ROW.getCode());
        out.write(width & 0xFF);
        for (int i = 0; i < width; i++) {
            out.write((int) ((value >> (8 * i)) & 0xFF));
        }
        return out.toByteArray();
    }

    static byte[] dateRow(java.time.LocalDate date) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.of(1, 1, 1), date);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.ROW.getCode());
        out.write(3);
        out.write((int) (days & 0xFF));
        out.write((int) ((days >> 8) & 0xFF));
        out.write((int) ((days >> 16) & 0xFF));
        return out.toByteArray();
    }

    static byte[] decimalRow(int precision, int scale, String text) {
        TdsColumnDefinition col = new TdsColumnDefinition(
                "amount", TdsTypeCode.DECIMALN, 1 + TdsBinaryValues.decimalIntegerWidth(precision),
                precision, scale, 1, 0, false, null);
        byte[] payload = TdsBinaryValues.encode(col, com.whosly.gateway.masking.MaskedValue.ofText(text, java.nio.charset.StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.ROW.getCode());
        out.write(payload.length & 0xFF);
        writeRaw(out, payload);
        return out.toByteArray();
    }

    static byte[] nvarcharPlpRow(String value) {
        byte[] utf16 = value.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.ROW.getCode());
        // ULONGLONGLEN
        long len = utf16.length;
        for (int i = 0; i < 8; i++) {
            out.write((int) ((len >> (8 * i)) & 0xFF));
        }
        // chunk
        writeU32(out, utf16.length);
        writeRaw(out, utf16);
        writeU32(out, 0);
        return out.toByteArray();
    }

    static byte[] nbcNvarcharRow(String presentOrNullLeft, String presentOrNullRight) {
        // bit0=left null?, bit1=right null?
        byte bitmap = 0;
        if (presentOrNullLeft == null) {
            bitmap |= 0x01;
        }
        if (presentOrNullRight == null) {
            bitmap |= 0x02;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.NBCROW.getCode());
        out.write(bitmap);
        if (presentOrNullLeft != null) {
            byte[] utf16 = presentOrNullLeft.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
            writeU16(out, utf16.length);
            writeRaw(out, utf16);
        }
        if (presentOrNullRight != null) {
            byte[] utf16 = presentOrNullRight.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
            writeU16(out, utf16.length);
            writeRaw(out, utf16);
        }
        return out.toByteArray();
    }


    static byte[] moneyColumnMeta(String name, boolean small, boolean nullable) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.COLMETADATA.getCode());
        writeU16(out, 1);
        writeU32(out, 0);
        writeU16(out, nullable ? 1 : 0);
        if (small) {
            out.write(TdsTypeCode.MONEY4TYPE.getCode());
        } else {
            out.write(TdsTypeCode.MONEYTYPE.getCode());
        }
        writeBVarchar(out, name);
        return out.toByteArray();
    }

    static byte[] moneynColumnMeta(String name, int maxLen, boolean nullable) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.COLMETADATA.getCode());
        writeU16(out, 1);
        writeU32(out, 0);
        writeU16(out, nullable ? 1 : 0);
        out.write(TdsTypeCode.MONEYN.getCode());
        out.write(maxLen & 0xFF);
        writeBVarchar(out, name);
        return out.toByteArray();
    }

    static byte[] moneyRow(String text, int width) {
        TdsColumnDefinition col = new TdsColumnDefinition(
                "m", width == 4 ? TdsTypeCode.MONEY4TYPE : TdsTypeCode.MONEYTYPE,
                width, 0, 0, 0, width, false, null);
        byte[] payload = TdsBinaryValues.encode(col,
                com.whosly.gateway.masking.MaskedValue.ofText(text, StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.ROW.getCode());
        writeRaw(out, payload);
        return out.toByteArray();
    }

    static byte[] moneynRow(String text, int width) {
        TdsColumnDefinition col = new TdsColumnDefinition(
                "m", TdsTypeCode.MONEYN, width, 0, 0, 1, width, false, null);
        byte[] payload = TdsBinaryValues.encode(col,
                com.whosly.gateway.masking.MaskedValue.ofText(text, StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.ROW.getCode());
        out.write(payload.length & 0xFF);
        writeRaw(out, payload);
        return out.toByteArray();
    }

    static byte[] textColumnMeta(String name, boolean ntext, boolean nullable) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.COLMETADATA.getCode());
        writeU16(out, 1);
        writeU32(out, 0);
        writeU16(out, nullable ? 1 : 0);
        out.write(ntext ? TdsTypeCode.NTEXT.getCode() : TdsTypeCode.TEXT.getCode());
        writeU32(out, 0x7FFFFFFF); // max length
        writeRaw(out, new byte[]{0x09, 0x04, 0x00, 0x00, 0x00}); // collation
        // PartTableName: 1 part "t"
        out.write(1);
        writeUsVarchar(out, "t");
        writeBVarchar(out, name);
        return out.toByteArray();
    }

    static byte[] imageColumnMeta(String name, boolean nullable) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.COLMETADATA.getCode());
        writeU16(out, 1);
        writeU32(out, 0);
        writeU16(out, nullable ? 1 : 0);
        out.write(TdsTypeCode.IMAGE.getCode());
        writeU32(out, 0x7FFFFFFF);
        out.write(1);
        writeUsVarchar(out, "t");
        writeBVarchar(out, name);
        return out.toByteArray();
    }

    static byte[] textPtrRow(String value, boolean unicode) {
        byte[] data = unicode
                ? value.getBytes(StandardCharsets.UTF_16LE)
                : value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.ROW.getCode());
        out.write(16); // textptr len
        for (int i = 0; i < 16; i++) {
            out.write(0xAB);
        }
        for (int i = 0; i < 8; i++) {
            out.write(0);
        }
        writeU32(out, data.length);
        writeRaw(out, data);
        return out.toByteArray();
    }

    static byte[] textPtrNullRow() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.ROW.getCode());
        writeU32(out, 0xFFFFFFFF);
        return out.toByteArray();
    }

    static byte[] varbinaryMaxColumnMeta(String name, boolean nullable) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.COLMETADATA.getCode());
        writeU16(out, 1);
        writeU32(out, 0);
        writeU16(out, nullable ? 1 : 0);
        out.write(TdsTypeCode.BIGVARBIN.getCode());
        writeU16(out, TdsColumnDefinition.MAXLENGTH_PLP);
        writeBVarchar(out, name);
        return out.toByteArray();
    }

    static byte[] varbinaryPlpRow(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.ROW.getCode());
        long len = data.length;
        for (int i = 0; i < 8; i++) {
            out.write((int) ((len >> (8 * i)) & 0xFF));
        }
        writeU32(out, data.length);
        writeRaw(out, data);
        writeU32(out, 0);
        return out.toByteArray();
    }

    static byte[] xmlColumnMeta(String name, boolean nullable) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.COLMETADATA.getCode());
        writeU16(out, 1);
        writeU32(out, 0);
        writeU16(out, nullable ? 1 : 0);
        out.write(TdsTypeCode.XML.getCode());
        out.write(0); // SCHEMA_PRESENT = 0
        writeBVarchar(out, name);
        return out.toByteArray();
    }

    static byte[] udtColumnMeta(String name, boolean nullable) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.COLMETADATA.getCode());
        writeU16(out, 1);
        writeU32(out, 0);
        writeU16(out, nullable ? 1 : 0);
        out.write(TdsTypeCode.UDT.getCode());
        writeU16(out, 8000); // MAX_BYTE_SIZE
        writeBVarchar(out, "db");
        writeBVarchar(out, "dbo");
        writeBVarchar(out, "MyUdt");
        writeUsVarchar(out, "MyUdt, Assembly");
        writeBVarchar(out, name);
        return out.toByteArray();
    }

    static byte[] plpNullRow() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TdsTokenType.ROW.getCode());
        for (int i = 0; i < 8; i++) {
            out.write(0xFF);
        }
        return out.toByteArray();
    }

    static byte[] plpOpaqueRow(byte[] data) {
        return varbinaryPlpRow(data);
    }

    private static void writeUsVarchar(ByteArrayOutputStream out, String value) {
        byte[] utf16 = value.getBytes(StandardCharsets.UTF_16LE);
        writeU16(out, utf16.length / 2);
        writeRaw(out, utf16);
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
