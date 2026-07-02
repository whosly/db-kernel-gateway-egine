package com.whosly.gateway.parser;

import com.alibaba.druid.sql.ast.SQLStatement;

/**
 * Interface for SQL parsing functionality.
 * 
 * This interface defines the contract for parsing SQL statements using
 * Alibaba Druid parser and performing various analysis operations.
 */
public interface SqlParser {

    /**
     * Parse a SQL string into an AST (Abstract Syntax Tree).
     *
     * @param sql the SQL string to parse
     * @return the parsed SQL statement
     * @throws SqlParseException if the SQL cannot be parsed
     */
    SQLStatement parse(String sql) throws SqlParseException;

    /**
     * Validate the syntax of a SQL statement.
     *
     * @param sql the SQL string to validate
     * @return true if the SQL is valid, false otherwise
     */
    boolean validate(String sql);

    /**
     * Extract table names from a SQL statement.
     *
     * @param sql the SQL string to analyze
     * @return array of table names referenced in the SQL
     */
    String[] extractTableNames(String sql);

    /**
     * Translate SQL from one dialect to another.
     *
     * @param sql the SQL string to translate
     * @param targetDialect the target SQL dialect
     * @return translated SQL string
     */
    String translate(String sql, SqlDialect targetDialect);
}