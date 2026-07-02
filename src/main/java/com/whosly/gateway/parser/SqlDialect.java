package com.whosly.gateway.parser;

/**
 * Enum representing different SQL dialects supported by the gateway.
 */
public enum SqlDialect {
    MYSQL("MySQL"),
    POSTGRESQL("PostgreSQL"),
    ORACLE("Oracle"),
    SQLSERVER("SQL Server"),
    GENERIC("Generic");

    private final String displayName;

    SqlDialect(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}