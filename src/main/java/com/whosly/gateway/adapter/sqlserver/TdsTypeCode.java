package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.masking.ColumnMetadata;

/**
 * MS-TDS TYPE_INFO type bytes used by COLMETADATA / ROW parsing.
 *
 * <p>Categories drive masking fail-closed decisions. Wire layout kind tells the
 * ROW codec how length/null is encoded.</p>
 */
public enum TdsTypeCode {

    NULLTYPE(0x1F, ColumnMetadata.Category.OTHER, WireLayout.UNSUPPORTED, "null"),
    IMAGE(0x22, ColumnMetadata.Category.BINARY, WireLayout.TEXTPTR, "image"),
    TEXT(0x23, ColumnMetadata.Category.TEXT, WireLayout.TEXTPTR, "text"),
    GUIDTYPE(0x24, ColumnMetadata.Category.UUID, WireLayout.BYTE_LEN, "uniqueidentifier"),
    INTN(0x26, ColumnMetadata.Category.NUMERIC, WireLayout.BYTE_LEN, "intn"),
    DATEN(0x28, ColumnMetadata.Category.TEMPORAL, WireLayout.BYTE_LEN, "date"),
    TIMEN(0x29, ColumnMetadata.Category.TEMPORAL, WireLayout.BYTE_LEN, "time"),
    DATETIME2N(0x2A, ColumnMetadata.Category.TEMPORAL, WireLayout.BYTE_LEN, "datetime2"),
    DATETIMEOFFSETN(0x2B, ColumnMetadata.Category.TEMPORAL, WireLayout.BYTE_LEN, "datetimeoffset"),
    INT1TYPE(0x30, ColumnMetadata.Category.NUMERIC, WireLayout.FIXED, "tinyint"),
    BITTYPE(0x32, ColumnMetadata.Category.BOOLEAN, WireLayout.FIXED, "bit"),
    INT2TYPE(0x34, ColumnMetadata.Category.NUMERIC, WireLayout.FIXED, "smallint"),
    INT4TYPE(0x38, ColumnMetadata.Category.NUMERIC, WireLayout.FIXED, "int"),
    DATETIM4TYPE(0x3A, ColumnMetadata.Category.TEMPORAL, WireLayout.FIXED, "smalldatetime"),
    FLT4TYPE(0x3B, ColumnMetadata.Category.NUMERIC, WireLayout.FIXED, "real"),
    MONEYTYPE(0x3C, ColumnMetadata.Category.NUMERIC, WireLayout.FIXED, "money"),
    DATETIMETYPE(0x3D, ColumnMetadata.Category.TEMPORAL, WireLayout.FIXED, "datetime"),
    FLT8TYPE(0x3E, ColumnMetadata.Category.NUMERIC, WireLayout.FIXED, "float"),
    NTEXT(0x63, ColumnMetadata.Category.TEXT, WireLayout.TEXTPTR, "ntext"),
    BITN(0x68, ColumnMetadata.Category.BOOLEAN, WireLayout.BYTE_LEN, "bitn"),
    DECIMALN(0x6A, ColumnMetadata.Category.NUMERIC, WireLayout.BYTE_LEN, "decimal"),
    NUMERICN(0x6C, ColumnMetadata.Category.NUMERIC, WireLayout.BYTE_LEN, "numeric"),
    FLOATN(0x6D, ColumnMetadata.Category.NUMERIC, WireLayout.BYTE_LEN, "floatn"),
    MONEYN(0x6E, ColumnMetadata.Category.NUMERIC, WireLayout.BYTE_LEN, "moneyn"),
    DATETIMN(0x6F, ColumnMetadata.Category.TEMPORAL, WireLayout.BYTE_LEN, "datetimn"),
    MONEY4TYPE(0x7A, ColumnMetadata.Category.NUMERIC, WireLayout.FIXED, "smallmoney"),
    INT8TYPE(0x7F, ColumnMetadata.Category.NUMERIC, WireLayout.FIXED, "bigint"),
    BIGVARBIN(0xA5, ColumnMetadata.Category.BINARY, WireLayout.USHORT_LEN, "varbinary"),
    BIGVARCHR(0xA7, ColumnMetadata.Category.TEXT, WireLayout.USHORT_LEN, "varchar"),
    BIGBINARY(0xAD, ColumnMetadata.Category.BINARY, WireLayout.USHORT_LEN, "binary"),
    BIGCHAR(0xAF, ColumnMetadata.Category.TEXT, WireLayout.USHORT_LEN, "char"),
    NVARCHAR(0xE7, ColumnMetadata.Category.TEXT, WireLayout.USHORT_LEN, "nvarchar"),
    NCHAR(0xEF, ColumnMetadata.Category.TEXT, WireLayout.USHORT_LEN, "nchar"),
    UDT(0xF0, ColumnMetadata.Category.OTHER, WireLayout.PLP_META, "udt"),
    /** XML wire is MS-BINXML; masking presents decoded text as TEXT. */
    XML(0xF1, ColumnMetadata.Category.TEXT, WireLayout.PLP_META, "xml"),
    TVP(0xF3, ColumnMetadata.Category.OTHER, WireLayout.TVP_META, "table");

    /** How a ROW value is length-prefixed / nulled on the wire. */
    public enum WireLayout {
        FIXED,
        BYTE_LEN,
        USHORT_LEN,
        TEXTPTR,
        /** Always PLP on the wire; TYPE_INFO carries XML_INFO / UDT_INFO, not USHORTMAXLEN. */
        PLP_META,
        /** Table-valued parameter TYPE_INFO (RPC); result-set cell rewrite stays fail-closed. */
        TVP_META,
        UNSUPPORTED
    }

    private final int code;
    private final ColumnMetadata.Category category;
    private final WireLayout layout;
    private final String typeName;

    TdsTypeCode(int code, ColumnMetadata.Category category, WireLayout layout, String typeName) {
        this.code = code;
        this.category = category;
        this.layout = layout;
        this.typeName = typeName;
    }

    public int getCode() {
        return code;
    }

    public ColumnMetadata.Category category() {
        return category;
    }

    public WireLayout layout() {
        return layout;
    }

    public String typeName() {
        return typeName;
    }

    public static TdsTypeCode fromCode(int code) {
        int normalized = code & 0xFF;
        for (TdsTypeCode type : values()) {
            if (type.code == normalized) {
                return type;
            }
        }
        return null;
    }

    public boolean isUnicodeText() {
        return this == NVARCHAR || this == NCHAR || this == NTEXT;
    }

    public boolean carriesCollation() {
        return this == BIGVARCHR || this == BIGCHAR || this == NVARCHAR || this == NCHAR;
    }

    public boolean carriesPartTableName() {
        return this == TEXT || this == NTEXT || this == IMAGE;
    }
}
