package com.whosly.gateway.parser;

/**
 * Exception thrown when SQL parsing fails.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class SqlParseException extends Exception {

    public SqlParseException(String message) {
        super(message);
    }

    public SqlParseException(String message, Throwable cause) {
        super(message, cause);
    }
}