package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.masking.ColumnMetadata;

import java.util.Objects;
import java.util.Optional;

/**
 * One COLMETADATA column with enough wire detail for ROW parse/rewrite.
 *
 * <p>{@link #plp} is true when maxlength is {@code 0xFFFF} (MAX / PLP). PLP and
 * TEXTPTR layouts are observed but non-null masking is refused (fail-closed).</p>
 */
public final class TdsColumnDefinition {

    /** USHORT maxlength meaning "(MAX)" / PLP on BIG* / N*CHAR types. */
    public static final int MAXLENGTH_PLP = 0xFFFF;

    private final String name;
    private final TdsTypeCode type;
    private final int maxLength;
    private final int precision;
    private final int scale;
    private final int flags;
    private final int fixedByteLength;
    private final boolean plp;
    private final byte[] collationBytes;

    public TdsColumnDefinition(
            String name,
            TdsTypeCode type,
            int maxLength,
            int precision,
            int scale,
            int flags,
            int fixedByteLength,
            boolean plp,
            byte[] collationBytes) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.maxLength = maxLength;
        this.precision = precision;
        this.scale = scale;
        this.flags = flags;
        this.fixedByteLength = fixedByteLength;
        this.plp = plp;
        this.collationBytes = collationBytes == null ? null : collationBytes.clone();
    }

    public String name() {
        return name;
    }

    public TdsTypeCode type() {
        return type;
    }

    public int maxLength() {
        return maxLength;
    }

    public int precision() {
        return precision;
    }

    public int scale() {
        return scale;
    }

    public int flags() {
        return flags;
    }

    public boolean nullable() {
        return (flags & 0x01) != 0;
    }

    public int fixedByteLength() {
        return fixedByteLength;
    }

    public boolean plp() {
        return plp;
    }

    public Optional<byte[]> collationBytes() {
        return collationBytes == null ? Optional.empty() : Optional.of(collationBytes.clone());
    }

    public ColumnMetadata toColumnMetadata() {
        // Present text/uuid as TEXT so shared rules (partial/hash/fixed) see UTF-8
        // strings after {@link SqlServerResultSetMaskingInterceptor#toRuleValue}.
        ColumnMetadata.ValueFormat format =
                (type.category() == ColumnMetadata.Category.TEXT
                        || type.category() == ColumnMetadata.Category.UUID)
                        ? ColumnMetadata.ValueFormat.TEXT
                        : ColumnMetadata.ValueFormat.BINARY;
        return new ColumnMetadata(
                name,
                Optional.empty(),
                type.typeName(),
                type.category(),
                format,
                nullable());
    }

    /**
     * True when non-null masking may rewrite this column's ROW value.
     * NULL masks are handled separately for every nullable layout we can encode.
     */
    public boolean supportsNonNullRewrite() {
        if (plp || type.layout() == TdsTypeCode.WireLayout.UNSUPPORTED
                || type.layout() == TdsTypeCode.WireLayout.TEXTPTR) {
            return false;
        }
        return type == TdsTypeCode.NVARCHAR
                || type == TdsTypeCode.NCHAR
                || type == TdsTypeCode.BIGVARCHR
                || type == TdsTypeCode.BIGCHAR
                || type == TdsTypeCode.GUIDTYPE;
    }

    public boolean supportsNullRewrite() {
        // FIXED types have no NULL marker in ROW (nullable ints use INTN etc.).
        if (type.layout() == TdsTypeCode.WireLayout.FIXED
                || type.layout() == TdsTypeCode.WireLayout.UNSUPPORTED) {
            return false;
        }
        if (plp) {
            return true; // PLP_NULL
        }
        return type.layout() == TdsTypeCode.WireLayout.BYTE_LEN
                || type.layout() == TdsTypeCode.WireLayout.USHORT_LEN
                || type.layout() == TdsTypeCode.WireLayout.TEXTPTR;
    }
}
