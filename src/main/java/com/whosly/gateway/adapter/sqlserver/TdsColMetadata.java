package com.whosly.gateway.adapter.sqlserver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses TDS {@code COLMETADATA} ({@code 0x81}) into {@link TdsColumnDefinition}s.
 *
 * <p>Best-effort / fail-closed: truncated or unknown TYPE_INFO yields empty so
 * the masking interceptor can refuse rather than guess column attribution.
 * XML (MS-BINXML) and UDT (hierarchyid / opaque hex) TYPE_INFO are fully parsed;
 * TVP_META is consumed so COLMETADATA can complete, but TVP cells stay fail-closed.</p>
 */
public final class TdsColMetadata {

    private TdsColMetadata() {
    }

    /**
     * @return empty when token is not COLMETADATA, Count is {@code 0xFFFF}
     *         (no metadata), or parsing cannot complete safely
     */
    public static Optional<Parsed> tryParse(byte[] payload, int offset) {
        if (payload == null || offset < 0 || offset + 3 > payload.length) {
            return Optional.empty();
        }
        if ((payload[offset] & 0xFF) != TdsTokenType.COLMETADATA.getCode()) {
            return Optional.empty();
        }
        int count = u16le(payload, offset + 1);
        if (count == 0xFFFF) {
            // NoMetaData — NBCROW-only result; cannot attribute columns.
            return Optional.empty();
        }
        int cursor = offset + 3;
        List<TdsColumnDefinition> columns = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Optional<ParseColumnResult> col = parseColumn(payload, cursor);
            if (col.isEmpty()) {
                return Optional.empty();
            }
            columns.add(col.get().definition());
            cursor = col.get().nextOffset();
        }
        return Optional.of(new Parsed(List.copyOf(columns), cursor - offset));
    }

    private static Optional<ParseColumnResult> parseColumn(byte[] payload, int offset) {
        if (offset + 6 > payload.length) {
            return Optional.empty();
        }
        // UserType ULONG + Flags USHORT
        int flags = u16le(payload, offset + 4);
        int cursor = offset + 6;
        Optional<TypeInfo> typeInfo = parseTypeInfo(payload, cursor);
        if (typeInfo.isEmpty()) {
            return Optional.empty();
        }
        cursor = typeInfo.get().nextOffset();
        TdsTypeCode type = typeInfo.get().type();
        if (type.carriesPartTableName()) {
            Optional<Integer> afterTable = skipPartTableName(payload, cursor);
            if (afterTable.isEmpty()) {
                return Optional.empty();
            }
            cursor = afterTable.get();
        }
        Optional<NameParse> name = readBVarchar(payload, cursor);
        if (name.isEmpty()) {
            return Optional.empty();
        }
        cursor = name.get().nextOffset();
        TdsColumnDefinition def = new TdsColumnDefinition(
                name.get().value(),
                type,
                typeInfo.get().maxLength(),
                typeInfo.get().precision(),
                typeInfo.get().scale(),
                flags,
                typeInfo.get().fixedByteLength(),
                typeInfo.get().plp(),
                typeInfo.get().collation(),
                typeInfo.get().udtTypeName());
        return Optional.of(new ParseColumnResult(def, cursor));
    }

    private static Optional<TypeInfo> parseTypeInfo(byte[] payload, int offset) {
        if (offset >= payload.length) {
            return Optional.empty();
        }
        int typeByte = payload[offset] & 0xFF;
        TdsTypeCode type = TdsTypeCode.fromCode(typeByte);
        if (type == null) {
            return Optional.empty();
        }
        int cursor = offset + 1;
        return switch (type.layout()) {
            case FIXED -> Optional.of(new TypeInfo(type, 0, 0, 0, fixedLength(type), false, null, null, cursor));
            case BYTE_LEN -> parseByteLenTypeInfo(payload, cursor, type);
            case USHORT_LEN -> parseUshortLenTypeInfo(payload, cursor, type);
            case TEXTPTR -> parseTextPtrTypeInfo(payload, cursor, type);
            case PLP_META -> parsePlpMetaTypeInfo(payload, cursor, type);
            case TVP_META -> parseTvpMetaTypeInfo(payload, cursor, type);
            case UNSUPPORTED -> Optional.empty();
        };
    }

    private static Optional<TypeInfo> parseByteLenTypeInfo(byte[] payload, int cursor, TdsTypeCode type) {
        if (type == TdsTypeCode.DATEN) {
            return Optional.of(new TypeInfo(type, 3, 0, 0, 3, false, null, null, cursor));
        }
        if (type == TdsTypeCode.TIMEN || type == TdsTypeCode.DATETIME2N || type == TdsTypeCode.DATETIMEOFFSETN) {
            if (cursor >= payload.length) {
                return Optional.empty();
            }
            int scale = payload[cursor] & 0xFF;
            return Optional.of(new TypeInfo(type, 0, 0, scale, 0, false, null, null, cursor + 1));
        }
        if (cursor >= payload.length) {
            return Optional.empty();
        }
        int maxLen = payload[cursor] & 0xFF;
        cursor++;
        int precision = 0;
        int scale = 0;
        if (type == TdsTypeCode.DECIMALN || type == TdsTypeCode.NUMERICN) {
            if (cursor + 2 > payload.length) {
                return Optional.empty();
            }
            precision = payload[cursor] & 0xFF;
            scale = payload[cursor + 1] & 0xFF;
            cursor += 2;
        }
        return Optional.of(new TypeInfo(type, maxLen, precision, scale, maxLen, false, null, null, cursor));
    }

    private static Optional<TypeInfo> parseUshortLenTypeInfo(byte[] payload, int cursor, TdsTypeCode type) {
        if (cursor + 2 > payload.length) {
            return Optional.empty();
        }
        int maxLen = u16le(payload, cursor);
        cursor += 2;
        boolean plp = maxLen == TdsColumnDefinition.MAXLENGTH_PLP;
        byte[] collation = null;
        if (type.carriesCollation()) {
            if (cursor + 5 > payload.length) {
                return Optional.empty();
            }
            collation = new byte[5];
            System.arraycopy(payload, cursor, collation, 0, 5);
            cursor += 5;
        }
        return Optional.of(new TypeInfo(type, maxLen, 0, 0, 0, plp, collation, null, cursor));
    }

    private static Optional<TypeInfo> parseTextPtrTypeInfo(byte[] payload, int cursor, TdsTypeCode type) {
        if (cursor + 4 > payload.length) {
            return Optional.empty();
        }
        // TEXT/NTEXT/IMAGE: ULONG length in TYPE_INFO then optional collation for TEXT/NTEXT
        int maxLen = u32le(payload, cursor);
        cursor += 4;
        byte[] collation = null;
        if (type == TdsTypeCode.TEXT || type == TdsTypeCode.NTEXT) {
            if (cursor + 5 > payload.length) {
                return Optional.empty();
            }
            collation = new byte[5];
            System.arraycopy(payload, cursor, collation, 0, 5);
            cursor += 5;
        }
        return Optional.of(new TypeInfo(type, maxLen, 0, 0, 0, false, collation, null, cursor));
    }

    private static Optional<TypeInfo> parsePlpMetaTypeInfo(byte[] payload, int cursor, TdsTypeCode type) {
        if (type == TdsTypeCode.XML) {
            if (cursor >= payload.length) {
                return Optional.empty();
            }
            int schemaPresent = payload[cursor] & 0xFF;
            cursor++;
            if (schemaPresent == 0x01) {
                Optional<NameParse> db = readBVarchar(payload, cursor);
                if (db.isEmpty()) {
                    return Optional.empty();
                }
                cursor = db.get().nextOffset();
                Optional<NameParse> schema = readBVarchar(payload, cursor);
                if (schema.isEmpty()) {
                    return Optional.empty();
                }
                cursor = schema.get().nextOffset();
                Optional<NameParse> collection = readUsVarchar(payload, cursor);
                if (collection.isEmpty()) {
                    return Optional.empty();
                }
                cursor = collection.get().nextOffset();
            } else if (schemaPresent != 0x00) {
                return Optional.empty();
            }
            // XML ROW values are always PLP (MS-BINXML); non-null rewrite via TdsBinXml.
            return Optional.of(new TypeInfo(
                    type, TdsColumnDefinition.MAXLENGTH_PLP, 0, 0, 0, true, null, null, cursor));
        }
        if (type == TdsTypeCode.UDT) {
            if (cursor + 2 > payload.length) {
                return Optional.empty();
            }
            int maxByteSize = u16le(payload, cursor);
            cursor += 2;
            Optional<NameParse> db = readBVarchar(payload, cursor);
            if (db.isEmpty()) {
                return Optional.empty();
            }
            cursor = db.get().nextOffset();
            Optional<NameParse> schema = readBVarchar(payload, cursor);
            if (schema.isEmpty()) {
                return Optional.empty();
            }
            cursor = schema.get().nextOffset();
            Optional<NameParse> typeName = readBVarchar(payload, cursor);
            if (typeName.isEmpty()) {
                return Optional.empty();
            }
            cursor = typeName.get().nextOffset();
            Optional<NameParse> assembly = readUsVarchar(payload, cursor);
            if (assembly.isEmpty()) {
                return Optional.empty();
            }
            cursor = assembly.get().nextOffset();
            // UDT ROW values use PLP; hierarchyid path / opaque 0x hex via TdsHierarchyId.
            return Optional.of(new TypeInfo(
                    type, maxByteSize, 0, 0, 0, true, null, typeName.get().value(), cursor));
        }
        return Optional.empty();
    }

    /**
     * TVP_TYPE_INFO: DbName + Schema + TypeName + Count(+ nested columns).
     * Consumed so COLMETADATA can complete; cell rewrite stays fail-closed.
     */
    private static Optional<TypeInfo> parseTvpMetaTypeInfo(byte[] payload, int cursor, TdsTypeCode type) {
        Optional<NameParse> db = readBVarchar(payload, cursor);
        if (db.isEmpty()) {
            return Optional.empty();
        }
        cursor = db.get().nextOffset();
        Optional<NameParse> schema = readBVarchar(payload, cursor);
        if (schema.isEmpty()) {
            return Optional.empty();
        }
        cursor = schema.get().nextOffset();
        Optional<NameParse> typeName = readBVarchar(payload, cursor);
        if (typeName.isEmpty()) {
            return Optional.empty();
        }
        cursor = typeName.get().nextOffset();
        if (cursor + 2 > payload.length) {
            return Optional.empty();
        }
        int count = u16le(payload, cursor);
        cursor += 2;
        if (count == 0xFFFF) {
            // TVP_NULL — no columns.
            return Optional.of(new TypeInfo(type, 0, 0, 0, 0, false, null, typeName.get().value(), cursor));
        }
        for (int i = 0; i < count; i++) {
            if (cursor + 4 > payload.length) {
                return Optional.empty();
            }
            cursor += 4; // UserType USHORT + Flags USHORT
            Optional<TypeInfo> nested = parseTypeInfo(payload, cursor);
            if (nested.isEmpty()) {
                return Optional.empty();
            }
            cursor = nested.get().nextOffset();
            Optional<NameParse> colName = readBVarchar(payload, cursor);
            if (colName.isEmpty()) {
                return Optional.empty();
            }
            cursor = colName.get().nextOffset();
        }
        // Optional TVP_ORDER_UNIQUE (0x10) / TVP_COLUMN_ORDERING (0x11) may follow in
        // some TDS versions inside the TVP value stream, not in COLMETADATA TYPE_INFO.
        return Optional.of(new TypeInfo(type, 0, 0, 0, 0, false, null, typeName.get().value(), cursor));
    }

    private static Optional<Integer> skipPartTableName(byte[] payload, int offset) {
        if (offset >= payload.length) {
            return Optional.empty();
        }
        int parts = payload[offset] & 0xFF;
        int cursor = offset + 1;
        for (int i = 0; i < parts; i++) {
            Optional<NameParse> part = readUsVarchar(payload, cursor);
            if (part.isEmpty()) {
                return Optional.empty();
            }
            cursor = part.get().nextOffset();
        }
        return Optional.of(cursor);
    }

    /** B_VARCHAR: BYTE char-count + UCS-2LE chars. */
    static Optional<NameParse> readBVarchar(byte[] payload, int offset) {
        if (offset >= payload.length) {
            return Optional.empty();
        }
        int chars = payload[offset] & 0xFF;
        int bytes = chars * 2;
        int dataStart = offset + 1;
        if (dataStart + bytes > payload.length) {
            return Optional.empty();
        }
        String value = chars == 0
                ? ""
                : new String(payload, dataStart, bytes, StandardCharsets.UTF_16LE);
        return Optional.of(new NameParse(value, dataStart + bytes));
    }

    /** US_VARCHAR: USHORT char-count + UCS-2LE chars. */
    static Optional<NameParse> readUsVarchar(byte[] payload, int offset) {
        if (offset + 2 > payload.length) {
            return Optional.empty();
        }
        int chars = u16le(payload, offset);
        int bytes = chars * 2;
        int dataStart = offset + 2;
        if (dataStart + bytes > payload.length) {
            return Optional.empty();
        }
        String value = chars == 0
                ? ""
                : new String(payload, dataStart, bytes, StandardCharsets.UTF_16LE);
        return Optional.of(new NameParse(value, dataStart + bytes));
    }

    private static int fixedLength(TdsTypeCode type) {
        return switch (type) {
            case INT1TYPE, BITTYPE -> 1;
            case INT2TYPE -> 2;
            case INT4TYPE, FLT4TYPE, DATETIM4TYPE, MONEY4TYPE -> 4;
            case FLT8TYPE, DATETIMETYPE, INT8TYPE -> 8;
            case MONEYTYPE -> 8;
            default -> 0;
        };
    }

    static int u16le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    static int u32le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    public record Parsed(List<TdsColumnDefinition> columns, int wireLength) {
    }

    private record ParseColumnResult(TdsColumnDefinition definition, int nextOffset) {
    }

    private record TypeInfo(
            TdsTypeCode type,
            int maxLength,
            int precision,
            int scale,
            int fixedByteLength,
            boolean plp,
            byte[] collation,
            String udtTypeName,
            int nextOffset) {
    }

    record NameParse(String value, int nextOffset) {
    }
}
