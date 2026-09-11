package com.whosly.gateway.adapter.postgresql;

import java.util.Arrays;
import java.util.Optional;

/**
 * PostgreSQL backend (server-to-client) message type codes.
 *
 * <p>Used to recognize the responses the gateway observes, such as
 * {@code ReadyForQuery} transaction state, {@code CommandComplete} command tags
 * and {@code ErrorResponse} SQLSTATE. Messages not listed here are still
 * forwarded, they are just not modeled yet.</p>
 *
 * @see <a href="https://www.postgresql.org/docs/current/protocol-message-formats.html">
 *     PostgreSQL Message Formats</a>
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public enum PostgreSQLBackendMessageType {

    /** Authentication request/result; the leading int4 is the authentication type. */
    AUTHENTICATION('R'),
    /** Session parameter reported by the server after authentication. */
    PARAMETER_STATUS('S'),
    /** Cancel key material for this session. */
    BACKEND_KEY_DATA('K'),
    /** Marks the connection idle and carries the transaction status byte. */
    READY_FOR_QUERY('Z'),
    /** Result set column metadata. */
    ROW_DESCRIPTION('T'),
    /** One result set row; NULL columns use length -1. */
    DATA_ROW('D'),
    /** End of a command, carrying its command tag. */
    COMMAND_COMPLETE('C'),
    /** Error with severity, SQLSTATE and message fields. */
    ERROR_RESPONSE('E'),
    /** Non-fatal warning or notice. */
    NOTICE_RESPONSE('N'),
    /** Asynchronous LISTEN/NOTIFY notification. */
    NOTIFICATION_RESPONSE('A'),
    /** Extended query: Parse succeeded. */
    PARSE_COMPLETE('1'),
    /** Extended query: Bind succeeded. */
    BIND_COMPLETE('2'),
    /** Extended query: Close succeeded. */
    CLOSE_COMPLETE('3'),
    /** Extended query: the statement or portal has no result set. */
    NO_DATA('n'),
    /** Extended query: parameter type OIDs. */
    PARAMETER_DESCRIPTION('t'),
    /** Extended query: portal hit its row limit and is suspended. */
    PORTAL_SUSPENDED('s'),
    /** Simple query: the query string was empty. */
    EMPTY_QUERY_RESPONSE('I'),
    /** COPY FROM STDIN started. */
    COPY_IN_RESPONSE('G'),
    /** COPY TO STDOUT started. */
    COPY_OUT_RESPONSE('H'),
    /** Replication COPY started in both directions. */
    COPY_BOTH_RESPONSE('W'),
    /** One chunk of COPY data. */
    COPY_DATA('d'),
    /** COPY finished. */
    COPY_DONE('c'),
    /** FunctionCall result. */
    FUNCTION_CALL_RESPONSE('V'),
    /** Server reports the highest minor protocol version it supports. */
    NEGOTIATE_PROTOCOL_VERSION('v');

    private final char code;

    PostgreSQLBackendMessageType(char code) {
        this.code = code;
    }

    public char getCode() {
        return code;
    }

    /**
     * Resolves a backend message code, returning empty for messages the gateway
     * does not model.
     */
    public static Optional<PostgreSQLBackendMessageType> fromCode(char code) {
        return Arrays.stream(values())
                .filter(messageType -> messageType.code == code)
                .findFirst();
    }
}
