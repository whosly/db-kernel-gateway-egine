package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.masking.MaskedValue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parse / re-encode TDS table-valued parameter (TVP) value streams.
 *
 * <p>Wire shape after TYPE_INFO column metadata (MS-TDS §2.2.5.5.4):</p>
 * <pre>
 *   [TVP_ORDER_UNIQUE 0x10 …]
 *   [TVP_COLUMN_ORDERING 0x11 …]
 *   TVP_END (0x00)          // end optional metadata
 *   *( TVP_ROW 0x01 + cells )
 *   TVP_END (0x00)          // end rows
 * </pre>
 *
 * <p>{@code Count=0xFFFF} (TVP_NULL) carries no value bytes. Nested cell layouts
 * reuse {@link TdsRowCodec} except TEXT/NTEXT/IMAGE which omit TextPointer+Timestamp
 * inside TVP_ROW. Columns with {@code fDefault} (Flags bit 0x200) omit cell data.</p>
 */
public final class TdsTvpCodec {

    public static final int ROW_TOKEN = 0x01;
    public static final int END_TOKEN = 0x00;
    public static final int ORDER_UNIQUE_TOKEN = 0x10;
    public static final int COLUMN_ORDERING_TOKEN = 0x11;
    /** COLMETADATA / TVP Flags: column uses server default — cell omitted in TVP_ROW. */
    public static final int FLAG_DEFAULT = 0x200;

    private TdsTvpCodec() {
    }

    /**
     * @param tvpNullType true when TYPE_INFO Count was {@code 0xFFFF}
     */
    public static Optional<ParsedTvp> tryParse(
            byte[] payload, int offset, List<TdsColumnDefinition> nestedColumns, boolean tvpNullType) {
        if (tvpNullType) {
            return Optional.of(new ParsedTvp(true, new byte[0], List.of(), 0));
        }
        if (payload == null || nestedColumns == null || offset < 0 || offset >= payload.length) {
            return Optional.empty();
        }
        int cursor = offset;
        // Optional ORDER_UNIQUE / COLUMN_ORDERING, then mandatory metadata END.
        ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        while (cursor < payload.length) {
            int token = payload[cursor] & 0xFF;
            if (token == ORDER_UNIQUE_TOKEN || token == COLUMN_ORDERING_TOKEN) {
                Optional<Integer> skipped = skipOptionalMetaToken(payload, cursor);
                if (skipped.isEmpty()) {
                    return Optional.empty();
                }
                int len = skipped.get();
                prefix.write(payload, cursor, len);
                cursor += len;
                continue;
            }
            break;
        }
        if (cursor >= payload.length || (payload[cursor] & 0xFF) != END_TOKEN) {
            return Optional.empty();
        }
        prefix.write(END_TOKEN);
        cursor++;

        List<List<MaskedValue>> rows = new ArrayList<>();
        while (cursor < payload.length) {
            int token = payload[cursor] & 0xFF;
            if (token == END_TOKEN) {
                cursor++;
                return Optional.of(new ParsedTvp(
                        false, prefix.toByteArray(), List.copyOf(rows), cursor - offset));
            }
            if (token != ROW_TOKEN) {
                return Optional.empty();
            }
            cursor++;
            List<MaskedValue> cells = new ArrayList<>(nestedColumns.size());
            for (TdsColumnDefinition nested : nestedColumns) {
                if (isDefaultColumn(nested)) {
                    cells.add(MaskedValue.ofNull()); // placeholder; not on wire
                    continue;
                }
                Optional<TdsRowCodec.ValueParse> cell =
                        TdsRowCodec.readCell(payload, cursor, nested, true);
                if (cell.isEmpty()) {
                    return Optional.empty();
                }
                cells.add(cell.get().value());
                cursor = cell.get().nextOffset();
            }
            rows.add(List.copyOf(cells));
        }
        return Optional.empty(); // truncated before row-end
    }

    /**
     * Encodes a TVP value. {@code prefix} must be the optional-meta bytes including the
     * trailing metadata {@link #END_TOKEN} from {@link ParsedTvp#prefix()}.
     */
    public static byte[] encode(
            List<TdsColumnDefinition> nestedColumns,
            byte[] prefix,
            List<List<MaskedValue>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (prefix != null && prefix.length > 0) {
            out.write(prefix, 0, prefix.length);
        } else {
            out.write(END_TOKEN);
        }
        for (List<MaskedValue> row : rows) {
            if (row.size() != nestedColumns.size()) {
                throw new IllegalArgumentException("TVP row cell count mismatch");
            }
            out.write(ROW_TOKEN);
            for (int i = 0; i < nestedColumns.size(); i++) {
                TdsColumnDefinition nested = nestedColumns.get(i);
                if (isDefaultColumn(nested)) {
                    continue;
                }
                TdsRowCodec.writeCell(out, nested, row.get(i), true);
            }
        }
        out.write(END_TOKEN);
        return out.toByteArray();
    }

    static boolean isDefaultColumn(TdsColumnDefinition column) {
        return (column.flags() & FLAG_DEFAULT) != 0;
    }

    /**
     * Skips TVP_ORDER_UNIQUE (0x10) or TVP_COLUMN_ORDERING (0x11) per MS-TDS §2.2.5.5.4.
     * ORDER_UNIQUE: BYTE + USHORT Count + Count×(USHORT ColNum + BYTE Flags).
     * COLUMN_ORDERING: BYTE + USHORT Count + Count×USHORT ColNum.
     */
    static Optional<Integer> skipOptionalMetaToken(byte[] payload, int offset) {
        if (offset >= payload.length) {
            return Optional.empty();
        }
        int token = payload[offset] & 0xFF;
        if (token != ORDER_UNIQUE_TOKEN && token != COLUMN_ORDERING_TOKEN) {
            return Optional.empty();
        }
        if (offset + 3 > payload.length) {
            return Optional.empty();
        }
        int count = TdsColMetadata.u16le(payload, offset + 1);
        int entrySize = token == ORDER_UNIQUE_TOKEN ? 3 : 2;
        int total = 1 + 2 + count * entrySize;
        if (offset + total > payload.length) {
            return Optional.empty();
        }
        return Optional.of(total);
    }

    public record ParsedTvp(
            boolean nullTvp,
            byte[] prefix,
            List<List<MaskedValue>> rows,
            int wireLength) {
    }
}
