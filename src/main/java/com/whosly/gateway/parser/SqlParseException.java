package com.whosly.gateway.parser;

/**
 * Exception thrown when SQL parsing fails.
 */
public class SqlParseException extends Exception {

    public SqlParseException(String message) {
        super(message);
    }

    public SqlParseException(String message, Throwable cause) {
        super(message, cause);
    }
}