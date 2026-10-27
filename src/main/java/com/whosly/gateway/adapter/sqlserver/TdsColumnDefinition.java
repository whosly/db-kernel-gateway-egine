package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.masking.ColumnMetadata;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One COLMETADATA column with enough wire detail for ROW parse/rewrite.
 *
 * <p>{@link #plp} is true when maxlength is {@code 0xFFFF} (MAX / PLP) or the type
 * is XML/UDT. Text/binary PLP, TEXTPTR, XML (MS-BINXML), hierarchyid UDT, and TVP
 * nested cells / wholesale outer-column rules can be rewritten; generic CLR UDT
 * non-null stays fail-closed unless the rule supplies opaque {@code 0x} hex;
 * unknown types stay fail-closed.</p>
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
    private final String udtTypeName;
    /** Nested TVP columns (empty when not TVP or TVP_NULL). */
    private final List<TdsColumnDefinition> nestedColumns;
    /** True when TVP TYPE_INFO Count was {@code 0xFFFF}. */
    private final boolean tvpNull;

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
        this(name, type, maxLength, precision, scale, flags, fixedByteLength, plp, collationBytes, null, List.of(), false);
    }

    public TdsColumnDefinition(
            String name,
            TdsTypeCode type,
            int maxLength,
            int precision,
            int scale,
            int flags,
            int fixedByteLength,
            boolean plp,
            byte[] collationBytes,
            String udtTypeName) {
        this(name, type, maxLength, precision, scale, flags, fixedByteLength, plp, collationBytes, udtTypeName, List.of(), false);
    }

    public TdsColumnDefinition(
            String name,
            TdsTypeCode type,
            int maxLength,
            int precision,
            int scale,
            int flags,
            int fixedByteLength,
            boolean plp,
            byte[] collationBytes,
            String udtTypeName,
            List<TdsColumnDefinition> nestedColumns,
            boolean tvpNull) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.maxLength = maxLength;
        this.precision = precision;
        this.scale = scale;
        this.flags = flags;
        this.fixedByteLength = fixedByteLength;
        this.plp = plp;
        this.collationBytes = collationBytes == null ? null : collationBytes.clone();
        this.udtTypeName = udtTypeName;
        this.nestedColumns = nestedColumns == null ? List.of() : List.copyOf(nestedColumns);
        this.tvpNull = tvpNull;
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

    public Optional<String> udtTypeName() {
        return udtTypeName == null || udtTypeName.isBlank()
                ? Optional.empty()
                : Optional.of(udtTypeName);
    }

    public List<TdsColumnDefinition> nestedColumns() {
        return nestedColumns;
    }

    public boolean tvpNull() {
        return tvpNull;
    }

    public boolean isHierarchyId() {
        return type == TdsTypeCode.UDT && TdsHierarchyId.isHierarchyIdTypeName(udtTypeName);
    }

    public ColumnMetadata toColumnMetadata() {
        ColumnMetadata.Category category = maskingCategory();
        ColumnMetadata.ValueFormat format =
                (category == ColumnMetadata.Category.TEXT
                        || category == ColumnMetadata.Category.UUID)
                        ? ColumnMetadata.ValueFormat.TEXT
                        : ColumnMetadata.ValueFormat.BINARY;
        String typeName = type.typeName();
        if (type == TdsTypeCode.UDT && udtTypeName != null && !udtTypeName.isBlank()) {
            typeName = udtTypeName;
        }
        return new ColumnMetadata(
                name,
                Optional.empty(),
                typeName,
                category,
                format,
                nullable());
    }

    private ColumnMetadata.Category maskingCategory() {
        if (type == TdsTypeCode.XML || isHierarchyId()) {
            return ColumnMetadata.Category.TEXT;
        }
        if (type == TdsTypeCode.UDT) {
            // Opaque hex fixed values claim BINARY.
            return ColumnMetadata.Category.BINARY;
        }
        return type.category();
    }

    /**
     * True when non-null masking may rewrite this column's ROW value.
     * NULL masks are handled separately for every nullable layout we can encode.
     */
    public boolean supportsNonNullRewrite() {
        if (type.layout() == TdsTypeCode.WireLayout.UNSUPPORTED) {
            return false;
        }
        if (type.layout() == TdsTypeCode.WireLayout.TVP_META) {
            // Wholesale: empty-table / broadcast into nested cells (see interceptor).
            return true;
        }
        if (type == TdsTypeCode.XML) {
            return true; // MS-BINXML via TdsBinXml
        }
        if (type == TdsTypeCode.UDT) {
            // hierarchyid path strings, or opaque 0x hex for other UDTs (checked at encode).
            return true;
        }
        if (type.layout() == TdsTypeCode.WireLayout.TEXTPTR) {
            return type == TdsTypeCode.TEXT
                    || type == TdsTypeCode.NTEXT
                    || type == TdsTypeCode.IMAGE;
        }
        if (plp) {
            return type == TdsTypeCode.NVARCHAR
                    || type == TdsTypeCode.NCHAR
                    || type == TdsTypeCode.BIGVARCHR
                    || type == TdsTypeCode.BIGCHAR
                    || type == TdsTypeCode.BIGVARBIN
                    || type == TdsTypeCode.BIGBINARY;
        }
        return type == TdsTypeCode.NVARCHAR
                || type == TdsTypeCode.NCHAR
                || type == TdsTypeCode.BIGVARCHR
                || type == TdsTypeCode.BIGCHAR
                || type == TdsTypeCode.BIGVARBIN
                || type == TdsTypeCode.BIGBINARY
                || type == TdsTypeCode.GUIDTYPE
                || TdsBinaryValues.isEncodable(type);
    }

    public boolean supportsNullRewrite() {
        // FIXED types have no NULL marker in ROW (nullable ints use INTN etc.).
        if (type.layout() == TdsTypeCode.WireLayout.FIXED
                || type.layout() == TdsTypeCode.WireLayout.UNSUPPORTED) {
            return false;
        }
        if (type.layout() == TdsTypeCode.WireLayout.TVP_META) {
            // TVP_NULL (Count=0xFFFF) or empty-table rewrite for wholesale null.
            return true;
        }
        if (plp || type.layout() == TdsTypeCode.WireLayout.PLP_META) {
            return true; // PLP_NULL
        }
        return type.layout() == TdsTypeCode.WireLayout.BYTE_LEN
                || type.layout() == TdsTypeCode.WireLayout.USHORT_LEN
                || type.layout() == TdsTypeCode.WireLayout.TEXTPTR;
    }
}
