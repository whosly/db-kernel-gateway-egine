package com.whosly.gateway.adapter.postgresql;

import java.util.Arrays;
import java.util.Optional;

/**
 * PostgreSQL built-in type OIDs commonly needed by protocol metadata handling.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public enum PostgreSQLTypeOid {
    BOOL(16, "bool", "boolean"),
    BYTEA(17, "bytea", "binary"),
    CHAR(18, "char", "internal"),
    NAME(19, "name", "identifier"),
    INT8(20, "int8", "integer"),
    INT2(21, "int2", "integer"),
    INT4(23, "int4", "integer"),
    TEXT(25, "text", "string"),
    OID(26, "oid", "object-id"),
    JSON(114, "json", "json"),
    XML(142, "xml", "xml"),
    FLOAT4(700, "float4", "floating"),
    FLOAT8(701, "float8", "floating"),
    MONEY(790, "money", "numeric"),
    INET(869, "inet", "network"),
    BPCHAR(1042, "bpchar", "string"),
    VARCHAR(1043, "varchar", "string"),
    DATE(1082, "date", "date-time"),
    TIME(1083, "time", "date-time"),
    TIMESTAMP(1114, "timestamp", "date-time"),
    TIMESTAMPTZ(1184, "timestamptz", "date-time"),
    INTERVAL(1186, "interval", "date-time"),
    NUMERIC(1700, "numeric", "numeric"),
    UUID(2950, "uuid", "uuid"),
    JSONB(3802, "jsonb", "json"),
    BOOL_ARRAY(1000, "_bool", "array"),
    BYTEA_ARRAY(1001, "_bytea", "array"),
    INT2_ARRAY(1005, "_int2", "array"),
    INT4_ARRAY(1007, "_int4", "array"),
    TEXT_ARRAY(1009, "_text", "array"),
    VARCHAR_ARRAY(1015, "_varchar", "array"),
    INT8_ARRAY(1016, "_int8", "array"),
    FLOAT4_ARRAY(1021, "_float4", "array"),
    FLOAT8_ARRAY(1022, "_float8", "array"),
    NUMERIC_ARRAY(1231, "_numeric", "array"),
    UUID_ARRAY(2951, "_uuid", "array"),
    JSONB_ARRAY(3807, "_jsonb", "array");

    private final int oid;
    private final String typeName;
    private final String category;

    PostgreSQLTypeOid(int oid, String typeName, String category) {
        this.oid = oid;
        this.typeName = typeName;
        this.category = category;
    }

    public int getOid() {
        return oid;
    }

    public String getTypeName() {
        return typeName;
    }

    public String getCategory() {
        return category;
    }

    public static Optional<PostgreSQLTypeOid> fromOid(int oid) {
        return Arrays.stream(values())
                .filter(type -> type.oid == oid)
                .findFirst();
    }
}
