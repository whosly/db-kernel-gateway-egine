package com.whosly.gateway.adapter.postgresql;

import java.util.Arrays;
import java.util.Optional;

/**
 * PostgreSQL backend (server-to-client) message type codes.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public enum PostgreSQLBackendMessageType {
    AUTHENTICATION('R'),
    PARAMETER_STATUS('S'),
    BACKEND_KEY_DATA('K'),
    READY_FOR_QUERY('Z'),
    ROW_DESCRIPTION('T'),
    DATA_ROW('D'),
    COMMAND_COMPLETE('C'),
    ERROR_RESPONSE('E'),
    NOTICE_RESPONSE('N'),
    NOTIFICATION_RESPONSE('A'),
    PARSE_COMPLETE('1'),
    BIND_COMPLETE('2'),
    CLOSE_COMPLETE('3'),
    NO_DATA('n'),
    PARAMETER_DESCRIPTION('t'),
    PORTAL_SUSPENDED('s'),
    EMPTY_QUERY_RESPONSE('I'),
    COPY_IN_RESPONSE('G'),
    COPY_OUT_RESPONSE('H'),
    COPY_BOTH_RESPONSE('W'),
    COPY_DATA('d'),
    COPY_DONE('c'),
    FUNCTION_CALL_RESPONSE('V'),
    NEGOTIATE_PROTOCOL_VERSION('v');

    private final char code;

    PostgreSQLBackendMessageType(char code) {
        this.code = code;
    }

    public char getCode() {
        return code;
    }

    public static Optional<PostgreSQLBackendMessageType> fromCode(char code) {
        return Arrays.stream(values())
                .filter(messageType -> messageType.code == code)
                .findFirst();
    }
}
