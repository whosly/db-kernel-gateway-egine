package com.whosly.gateway.masking;

import java.util.Objects;
import java.util.Optional;

/**
 * Protocol-neutral description of one result-set column.
 *
 * <p>Masking rules match on this, never on protocol internals, so the same rule
 * works for MySQL and PostgreSQL as long as the metadata it needs is available.
 * The protocol layers translate their own metadata ({@code ColumnDefinition},
 * {@code RowDescription}) into this type.</p>
 *
 * @param name      column name as declared by the database
 * @param tableName originating table, when the protocol exposes it
 * @param typeName  database type name, when it can be resolved
 * @param category  coarse value category, used to reject unsafe masking
 * @param format    wire format of the values in this column
 * @param nullable  whether the column accepts NULL
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public record ColumnMetadata(
        String name,
        Optional<String> tableName,
        String typeName,
        Category category,
        ValueFormat format,
        boolean nullable) {

    /**
     * Coarse value category.
     *
     * <p>A rule declares which categories it can produce valid values for. This
     * is what stops a rule from writing {@code ****} into an {@code int4} column,
     * which the client would fail to parse.</p>
     */
    public enum Category {
        TEXT,
        NUMERIC,
        TEMPORAL,
        BOOLEAN,
        BINARY,
        OTHER
    }

    /** Wire format of the values carried in a column. */
    public enum ValueFormat {
        /** Values are protocol text, as in the MySQL text protocol. */
        TEXT,
        /** Values are type-encoded binary, as in the PG binary format. */
        BINARY
    }

    public ColumnMetadata {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(tableName, "tableName must not be null");
        Objects.requireNonNull(typeName, "typeName must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(format, "format must not be null");
    }

    /**
     * Convenience for a nullable text column in text format. Used by rules and
     * tests; the protocol layers build full metadata.
     */
    public static ColumnMetadata text(String name) {
        return new ColumnMetadata(name, Optional.empty(), "text", Category.TEXT, ValueFormat.TEXT, true);
    }
}
