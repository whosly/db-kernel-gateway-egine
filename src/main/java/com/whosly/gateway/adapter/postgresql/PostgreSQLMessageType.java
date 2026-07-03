package com.whosly.gateway.adapter.postgresql;

import java.util.Arrays;
import java.util.Optional;

/**
 * PostgreSQL frontend message type codes.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public enum PostgreSQLMessageType {
    BIND('B'),
    CLOSE('C'),
    DESCRIBE('D'),
    EXECUTE('E'),
    FLUSH('H'),
    PARSE('P'),
    PASSWORD_MESSAGE('p'),
    QUERY('Q'),
    SYNC('S'),
    TERMINATE('X');

    private final char code;

    PostgreSQLMessageType(char code) {
        this.code = code;
    }

    public char getCode() {
        return code;
    }

    public static Optional<PostgreSQLMessageType> fromCode(char code) {
        return Arrays.stream(values())
                .filter(messageType -> messageType.code == code)
                .findFirst();
    }
}
