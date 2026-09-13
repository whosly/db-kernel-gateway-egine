package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.masking.ColumnMetadata;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Translates a PostgreSQL {@code RowDescription} into protocol-neutral column
 * metadata for the masking engine.
 *
 * <p>A {@code RowDescription} is where PostgreSQL announces the shape of a result
 * set, and — unlike MySQL — it also carries each column's wire format, so text and
 * binary columns are known before the first row arrives. What it does <em>not</em>
 * carry is any catalog information: the table is given as an OID, not a name, so
 * {@link ColumnMetadata#tableName()} stays empty rather than pretending to know a
 * name this layer cannot resolve.</p>
 *
 * <p>Fields that cannot be parsed are dropped instead of guessed at; the caller
 * compares the parsed column count with the declared one and refuses the result set
 * when they disagree, because a value that cannot be attributed to a column cannot
 * be masked correctly (rule 8.2).</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class PostgreSQLColumnMetadata {

    private PostgreSQLColumnMetadata() {
    }

    /**
     * Parses the fields of a {@code RowDescription} payload.
     *
     * @param payload buffer holding the message
     * @param offset  payload start, past the type byte and length
     * @param length  payload length
     * @return one entry per field that could be parsed, in column order
     */
    public static List<ColumnMetadata> parse(byte[] payload, int offset, int length) {
        List<ColumnMetadata> columns = new ArrayList<>();
        if (payload == null || offset < 0 || length < 2 || offset + length > payload.length) {
            return columns;
        }

        int end = offset + length;
        int fieldCount = readInt2(payload, offset);
        int cursor = offset + 2;
        for (int index = 0; index < fieldCount; index++) {
            CString name = readCString(payload, cursor, end);
            if (name == null) {
                break;
            }
            cursor = name.nextOffset;
            // table OID, column attribute, type OID, type size, type modifier, format.
            if (cursor + 18 > end) {
                break;
            }
            int typeOid = readInt4(payload, cursor + 6);
            int formatCode = readInt2(payload, cursor + 16);
            cursor += 18;

            columns.add(new ColumnMetadata(
                    name.value,
                    Optional.empty(),
                    typeName(typeOid),
                    categoryOf(typeOid),
                    formatCode == 0 ? ColumnMetadata.ValueFormat.TEXT : ColumnMetadata.ValueFormat.BINARY,
                    /*
                     * RowDescription carries no nullability: it lives in the catalog, not
                     * on the wire. Reporting nullable is the safe direction for masking —
                     * a rule that requires a nullable column still applies, whereas the
                     * opposite would silently skip masking a column the deployment asked
                     * to protect.
                     */
                    true));
        }
        return columns;
    }

    /** Category of a type OID, reusing the OID table's own classification. */
    public static ColumnMetadata.Category categoryOf(int typeOid) {
        return PostgreSQLTypeOid.fromOid(typeOid)
                .map(oid -> switch (oid.getCategory()) {
                    case "string", "identifier", "json", "xml" -> ColumnMetadata.Category.TEXT;
                    case "integer", "numeric", "floating", "object-id" -> ColumnMetadata.Category.NUMERIC;
                    case "boolean" -> ColumnMetadata.Category.BOOLEAN;
                    case "date-time" -> ColumnMetadata.Category.TEMPORAL;
                    case "binary" -> ColumnMetadata.Category.BINARY;
                    // uuid, arrays, network, internal and anything unknown: a text rule
                    // cannot produce a value the client's type model accepts.
                    default -> ColumnMetadata.Category.OTHER;
                })
                .orElse(ColumnMetadata.Category.OTHER);
    }

    /** Type name of an OID, or its numeric form when the table does not know it. */
    public static String typeName(int typeOid) {
        return PostgreSQLTypeOid.fromOid(typeOid)
                .map(PostgreSQLTypeOid::getTypeName)
                .orElse("oid:" + typeOid);
    }

    private static int readInt2(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static int readInt4(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    /** A NUL-terminated string, or {@code null} when the terminator is missing. */
    private static CString readCString(byte[] bytes, int offset, int endExclusive) {
        int terminator = offset;
        while (terminator < endExclusive && bytes[terminator] != 0) {
            terminator++;
        }
        if (terminator >= endExclusive) {
            return null;
        }
        return new CString(new String(bytes, offset, terminator - offset, StandardCharsets.UTF_8),
                terminator + 1);
    }

    private record CString(String value, int nextOffset) {
    }

    /**
     * Appends a {@code RowDescription} for tests and tools.
     *
     * <p>Kept next to the parser so the two can never disagree about the layout.</p>
     *
     * @param fields tableOid, typeOid and format code per field, plus its name
     * @return the message payload, without the type byte and length
     */
    public static byte[] payload(List<Field> fields) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write((fields.size() >> 8) & 0xFF);
        payload.write(fields.size() & 0xFF);
        for (Field field : fields) {
            payload.writeBytes(field.name().getBytes(StandardCharsets.UTF_8));
            payload.write(0);
            payload.writeBytes(int4(field.tableOid()));
            payload.writeBytes(new byte[]{0x00, 0x00});     // column attribute number
            payload.writeBytes(int4(field.typeOid()));
            payload.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFF});   // type size: variable
            payload.writeBytes(int4(-1));                   // type modifier: none
            payload.write((field.formatCode() >> 8) & 0xFF);
            payload.write(field.formatCode() & 0xFF);
        }
        return payload.toByteArray();
    }

    private static byte[] int4(int value) {
        return new byte[]{
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)};
    }

    /** One field of a {@code RowDescription}. */
    public record Field(String name, int tableOid, int typeOid, int formatCode) {
    }
}
