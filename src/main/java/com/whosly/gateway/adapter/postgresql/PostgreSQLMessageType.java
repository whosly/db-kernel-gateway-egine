package com.whosly.gateway.adapter.postgresql;

import java.util.Arrays;
import java.util.Optional;

/**
 * PostgreSQL frontend (client-to-server) message type codes.
 *
 * <p>Each frontend message is one type byte, a 4-byte big-endian length, then
 * the payload. The gateway observes these messages for audit and risk control
 * but always forwards them verbatim.</p>
 *
 * @see <a href="https://www.postgresql.org/docs/current/protocol-message-formats.html">
 *     PostgreSQL Message Formats</a>
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public enum PostgreSQLMessageType {

    /** Extended query: bind a prepared statement to a portal. */
    BIND('B'),
    /** Extended query: close a prepared statement or portal. */
    CLOSE('C'),
    /** Extended query: describe a prepared statement or portal. */
    DESCRIBE('D'),
    /** Extended query: execute a portal. */
    EXECUTE('E'),
    /** Extended query: flush pending output without creating a sync point. */
    FLUSH('H'),
    /** Extended query: parse a statement, optionally with parameter types. */
    PARSE('P'),
    /** Password or SASL authentication response. */
    PASSWORD_MESSAGE('p'),
    /** Simple query: one or more SQL statements in a single message. */
    QUERY('Q'),
    /** Extended query: error-recovery sync point, answered with ReadyForQuery. */
    SYNC('S'),
    /** Client terminates the session. */
    TERMINATE('X');

    private final char code;

    PostgreSQLMessageType(char code) {
        this.code = code;
    }

    public char getCode() {
        return code;
    }

    /**
     * Resolves a frontend message code, returning empty for messages the gateway
     * does not need to name.
     */
    public static Optional<PostgreSQLMessageType> fromCode(char code) {
        return Arrays.stream(values())
                .filter(messageType -> messageType.code == code)
                .findFirst();
    }
}
