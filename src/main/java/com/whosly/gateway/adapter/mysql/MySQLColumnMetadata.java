package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.masking.ColumnMetadata;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Translates a MySQL {@code ColumnDefinition} packet into the protocol-neutral
 * {@link ColumnMetadata} that masking rules match on.
 *
 * <p>Masking rules must never see protocol internals, so this is the only place
 * that knows the {@code ColumnDefinition} layout: six length-encoded strings
 * (catalog, schema, table, original table, name, original name), a fixed-field
 * length, the collation, the declared length, the type byte, the flags and the
 * decimals.</p>
 *
 * <p>Two details matter for safety rather than for parsing:</p>
 * <ul>
 *   <li><b>Category</b> stops a rule from writing {@code ****} into a {@code long}
 *       column, which the client would fail to parse.</li>
 *   <li><b>Binary-ness</b> is decided by the collation, not by the type name: a
 *       {@code VARBINARY} and a {@code VARCHAR} share a type code and differ only
 *       in collation. Getting this wrong would corrupt binary values.</li>
 * </ul>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class MySQLColumnMetadata {

    /** The binary collation: values in this column are raw bytes, not text. */
    private static final int BINARY_COLLATION = 63;

    /**
     * {@code NOT_NULL_FLAG} of the column definition.
     *
     * <p>Carried through because a rule that writes NULL must not do so into a column
     * the schema declares NOT NULL: the client would receive a result its own model
     * says cannot happen.</p>
     */
    private static final int NOT_NULL_FLAG = 0x0001;

    private static final int TYPE_DECIMAL = 0x00;
    private static final int TYPE_TINY = 0x01;
    private static final int TYPE_SHORT = 0x02;
    private static final int TYPE_LONG = 0x03;
    private static final int TYPE_FLOAT = 0x04;
    private static final int TYPE_DOUBLE = 0x05;
    private static final int TYPE_NULL = 0x06;
    private static final int TYPE_TIMESTAMP = 0x07;
    private static final int TYPE_LONGLONG = 0x08;
    private static final int TYPE_INT24 = 0x09;
    private static final int TYPE_DATE = 0x0A;
    private static final int TYPE_TIME = 0x0B;
    private static final int TYPE_DATETIME = 0x0C;
    private static final int TYPE_YEAR = 0x0D;
    private static final int TYPE_NEWDATE = 0x0E;
    private static final int TYPE_VARCHAR = 0x0F;
    private static final int TYPE_BIT = 0x10;
    private static final int TYPE_JSON = 0xF5;
    private static final int TYPE_NEWDECIMAL = 0xF6;
    private static final int TYPE_ENUM = 0xF7;
    private static final int TYPE_SET = 0xF8;
    private static final int TYPE_TINY_BLOB = 0xF9;
    private static final int TYPE_MEDIUM_BLOB = 0xFA;
    private static final int TYPE_LONG_BLOB = 0xFB;
    private static final int TYPE_BLOB = 0xFC;
    private static final int TYPE_VAR_STRING = 0xFD;
    private static final int TYPE_STRING = 0xFE;
    private static final int TYPE_GEOMETRY = 0xFF;

    private MySQLColumnMetadata() {
    }

    /**
     * Parses one {@code ColumnDefinition} packet.
     *
     * @param packet        buffer holding the packet
     * @param payloadOffset start of the payload, past the header
     * @param payloadLength payload length
     * @param format        wire format of the values that follow, which depends on
     *                      the command rather than on the column
     * @return the metadata, or empty when the packet cannot be parsed — the caller
     *         must then treat the result set as impossible to mask safely
     */
    public static Optional<ColumnMetadata> parse(byte[] packet, int payloadOffset, int payloadLength,
                                                 ColumnMetadata.ValueFormat format) {
        if (packet == null || payloadLength <= 0 || payloadOffset < 0
                || payloadOffset + payloadLength > packet.length) {
            return Optional.empty();
        }

        Cursor cursor = new Cursor(packet, payloadOffset, payloadOffset + payloadLength);
        try {
            cursor.readLengthEncodedString();                 // catalog ("def")
            cursor.readLengthEncodedString();                 // schema
            String table = cursor.readLengthEncodedString();  // table alias
            cursor.readLengthEncodedString();                 // original table
            String name = cursor.readLengthEncodedString();   // column name
            cursor.readLengthEncodedString();                 // original column name
            cursor.readLengthEncodedInteger();                // length of the fixed fields
            // Everything after the strings is fixed width, not length-encoded.
            int collation = cursor.readUnsignedShort();
            cursor.readUnsignedInt();                         // declared column length
            int type = cursor.readUnsignedByte();
            int flags = cursor.readUnsignedShort();

            return Optional.of(new ColumnMetadata(
                    name,
                    table.isEmpty() ? Optional.empty() : Optional.of(table),
                    typeName(type),
                    categoryOf(type, collation),
                    format,
                    (flags & NOT_NULL_FLAG) == 0));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /** Coarse category of a column, used to refuse unsafe masking. */
    static ColumnMetadata.Category categoryOf(int type, int collation) {
        return switch (type) {
            case TYPE_DECIMAL, TYPE_TINY, TYPE_SHORT, TYPE_LONG, TYPE_FLOAT, TYPE_DOUBLE,
                 TYPE_LONGLONG, TYPE_INT24, TYPE_NEWDECIMAL -> ColumnMetadata.Category.NUMERIC;
            case TYPE_TIMESTAMP, TYPE_DATE, TYPE_TIME, TYPE_DATETIME, TYPE_YEAR, TYPE_NEWDATE ->
                    ColumnMetadata.Category.TEMPORAL;
            case TYPE_BIT, TYPE_GEOMETRY -> ColumnMetadata.Category.BINARY;
            case TYPE_NULL -> ColumnMetadata.Category.OTHER;
            default -> isBinaryCollation(collation)
                    ? ColumnMetadata.Category.BINARY
                    : ColumnMetadata.Category.TEXT;
        };
    }

    private static boolean isBinaryCollation(int collation) {
        return collation == BINARY_COLLATION;
    }

    /** Database type name, for logs and for rules that match on type. */
    static String typeName(int type) {
        return switch (type) {
            case TYPE_DECIMAL -> "decimal";
            case TYPE_TINY -> "tinyint";
            case TYPE_SHORT -> "smallint";
            case TYPE_LONG -> "int";
            case TYPE_FLOAT -> "float";
            case TYPE_DOUBLE -> "double";
            case TYPE_NULL -> "null";
            case TYPE_TIMESTAMP -> "timestamp";
            case TYPE_LONGLONG -> "bigint";
            case TYPE_INT24 -> "mediumint";
            case TYPE_DATE -> "date";
            case TYPE_TIME -> "time";
            case TYPE_DATETIME -> "datetime";
            case TYPE_YEAR -> "year";
            case TYPE_NEWDATE -> "date";
            case TYPE_VARCHAR -> "varchar";
            case TYPE_BIT -> "bit";
            case TYPE_JSON -> "json";
            case TYPE_NEWDECIMAL -> "decimal";
            case TYPE_ENUM -> "enum";
            case TYPE_SET -> "set";
            case TYPE_TINY_BLOB -> "tinyblob";
            case TYPE_MEDIUM_BLOB -> "mediumblob";
            case TYPE_LONG_BLOB -> "longblob";
            case TYPE_BLOB -> "blob";
            case TYPE_VAR_STRING -> "varbinary_or_varchar";
            case TYPE_STRING -> "binary_or_char";
            case TYPE_GEOMETRY -> "geometry";
            default -> "type_" + type;
        };
    }

    /**
     * Minimal reader over a ColumnDefinition payload.
     *
     * <p>Every read is bounds-checked and throws {@link IllegalArgumentException}
     * instead of reading past the end: a malformed packet must yield "no metadata",
     * which the masking path treats as "cannot mask safely".</p>
     */
    private static final class Cursor {

        private final byte[] bytes;
        private final int end;
        private int position;

        private Cursor(byte[] bytes, int position, int end) {
            this.bytes = bytes;
            this.position = position;
            this.end = end;
        }

        private boolean hasRemaining() {
            return position < end;
        }

        private int readUnsignedByte() {
            if (!hasRemaining()) {
                throw new IllegalArgumentException("column definition ended early");
            }
            return bytes[position++] & 0xFF;
        }

        private int readUnsignedShort() {
            return readUnsignedByte() | (readUnsignedByte() << 8);
        }

        private long readUnsignedInt() {
            return readUnsignedByte() | ((long) readUnsignedByte() << 8)
                    | ((long) readUnsignedByte() << 16) | ((long) readUnsignedByte() << 24);
        }

        private long readLengthEncodedInteger() {
            int first = readUnsignedByte();
            if (first < 0xFB) {
                return first;
            }
            return switch (first) {
                case 0xFC -> readUnsignedByte() | ((long) readUnsignedByte() << 8);
                case 0xFD -> readUnsignedByte() | ((long) readUnsignedByte() << 8)
                        | ((long) readUnsignedByte() << 16);
                case 0xFE -> readUnsignedByte() | ((long) readUnsignedByte() << 8)
                        | ((long) readUnsignedByte() << 16) | ((long) readUnsignedByte() << 24)
                        | ((long) readUnsignedByte() << 32) | ((long) readUnsignedByte() << 40)
                        | ((long) readUnsignedByte() << 48) | ((long) readUnsignedByte() << 56);
                default -> throw new IllegalArgumentException("unsupported length-encoded integer prefix " + first);
            };
        }

        /**
         * MySQL length-encoded strings use the same prefix scheme; a {@code 0xFB}
         * prefix means NULL, which cannot appear in a column definition.
         */
        private String readLengthEncodedString() {
            long length = readLengthEncodedInteger();
            if (length > end - position) {
                throw new IllegalArgumentException("column definition string runs past the packet");
            }
            String value = new String(bytes, position, (int) length, StandardCharsets.UTF_8);
            position += (int) length;
            return value;
        }
    }
}
