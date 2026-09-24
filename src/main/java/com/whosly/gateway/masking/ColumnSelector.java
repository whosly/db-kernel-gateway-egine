package com.whosly.gateway.masking;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Decides which columns a rule targets.
 *
 * <p>Selectors are composable so a deployment can express the columns it cares
 * about without writing a rule per column.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@FunctionalInterface
public interface ColumnSelector {

    boolean matches(ColumnMetadata column);

    /** Every column. */
    static ColumnSelector all() {
        return column -> true;
    }

    /** A column with this exact name, case-insensitively. */
    static ColumnSelector named(String columnName) {
        Objects.requireNonNull(columnName, "columnName must not be null");
        return column -> column.name().equalsIgnoreCase(columnName);
    }

    /**
     * Columns whose name matches the regular expression, case-insensitively.
     * An over-broad pattern is the deployment's choice; the engine never widens
     * a selector on its own.
     */
    static ColumnSelector namePattern(String regex) {
        Objects.requireNonNull(regex, "regex must not be null");
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        return column -> pattern.matcher(column.name()).matches();
    }

    /** Columns originating from this table, case-insensitively. */
    static ColumnSelector inTable(String tableName) {
        Objects.requireNonNull(tableName, "tableName must not be null");
        return column -> column.tableName()
                .filter(name -> name.equalsIgnoreCase(tableName))
                .isPresent();
    }

    /** This selector or the other. */
    default ColumnSelector or(ColumnSelector other) {
        Objects.requireNonNull(other, "other must not be null");
        return column -> this.matches(column) || other.matches(column);
    }

    /** This selector and the other. */
    default ColumnSelector and(ColumnSelector other) {
        Objects.requireNonNull(other, "other must not be null");
        return column -> this.matches(column) && other.matches(column);
    }
}
