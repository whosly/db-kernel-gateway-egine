package com.whosly.gateway.adapter.mysql;

import java.util.Arrays;
import java.util.Optional;

/**
 * MySQL command phase command codes with their observation metadata.
 *
 * <p>A client sends one of these as the first payload byte of a command packet
 * once the connection phase has finished. The gateway only observes the code:
 * SQL text is extracted for audit, while the command packet itself is forwarded
 * verbatim to the target database.</p>
 *
 * <p>Every command declares whether the target answers it at all
 * ({@link #expectsResponse()}) and which {@link MySQLResponseShape} that answer
 * takes. MySQL response framing is not self describing, so the observation phase
 * machine branches on this declaration instead of guessing from the first
 * payload byte (rule 3.6).</p>
 *
 * <p>The codes marked {@link MySQLResponseShape#UNKNOWN} are server-internal: a
 * client must never send them, so observing one suspends observation instead of
 * predicting a response.</p>
 *
 * @see <a href="https://dev.mysql.com/doc/dev/mysql-server/latest/page_protocol_command_phase.html">
 *     MySQL Command Phase</a>
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public enum MySQLCommandType {

    /** Internal server state, never sent by a client. */
    COM_SLEEP(0x00, false, MySQLResponseShape.UNKNOWN),
    /** Client closes the connection; the server tears the session down. */
    COM_QUIT(0x01, false, MySQLResponseShape.NO_RESPONSE),
    /** Switch the session default database. */
    COM_INIT_DB(0x02, true, MySQLResponseShape.OK),
    /** Text protocol SQL query; the main audited command. */
    COM_QUERY(0x03, true, MySQLResponseShape.RESULTSET),
    /** List a table's columns (deprecated); answered without a column count packet. */
    COM_FIELD_LIST(0x04, true, MySQLResponseShape.COLUMN_LIST),
    /** Create a database (deprecated). */
    COM_CREATE_DB(0x05, true, MySQLResponseShape.OK),
    /** Drop a database (deprecated). */
    COM_DROP_DB(0x06, true, MySQLResponseShape.OK),
    /** Flush tables, logs, caches or privileges. */
    COM_REFRESH(0x07, true, MySQLResponseShape.OK),
    /** Shut the server down; requires the SHUTDOWN privilege. */
    COM_SHUTDOWN(0x08, true, MySQLResponseShape.OK),
    /** Return an unmarked status string for monitoring. */
    COM_STATISTICS(0x09, true, MySQLResponseShape.RAW_STRING),
    /** Equivalent to SHOW PROCESSLIST (deprecated). */
    COM_PROCESS_INFO(0x0A, true, MySQLResponseShape.RESULTSET),
    /** Internal server packet, never sent by a client. */
    COM_CONNECT(0x0B, false, MySQLResponseShape.UNKNOWN),
    /** Kill another connection (deprecated). */
    COM_PROCESS_KILL(0x0C, true, MySQLResponseShape.OK),
    /** Dump internal debug information. */
    COM_DEBUG(0x0D, true, MySQLResponseShape.EOF_ONLY),
    /** Heartbeat; the server replies with an OK packet. */
    COM_PING(0x0E, true, MySQLResponseShape.OK),
    /** Internal server packet, never sent by a client. */
    COM_TIME(0x0F, false, MySQLResponseShape.UNKNOWN),
    /** Deprecated delayed-insert command. */
    COM_DELAYED_INSERT(0x10, true, MySQLResponseShape.OK),
    /** Re-authenticate and switch user on the same connection. */
    COM_CHANGE_USER(0x11, true, MySQLResponseShape.OK),
    /** Replication: request a binlog stream; the response never ends. */
    COM_BINLOG_DUMP(0x12, true, MySQLResponseShape.STREAM),
    /** Replication: request a table dump (deprecated). */
    COM_TABLE_DUMP(0x13, true, MySQLResponseShape.STREAM),
    /** Internal server packet, never sent by a client. */
    COM_CONNECT_OUT(0x14, false, MySQLResponseShape.UNKNOWN),
    /** Replication: replica registration (deprecated). */
    COM_REGISTER_SLAVE(0x15, true, MySQLResponseShape.OK),
    /** Prepare a statement; answers with metadata counts before the definitions. */
    COM_STMT_PREPARE(0x16, true, MySQLResponseShape.PREPARE),
    /** Execute a prepared statement. */
    COM_STMT_EXECUTE(0x17, true, MySQLResponseShape.RESULTSET),
    /** Send one parameter value of a prepared statement before execute; no answer. */
    COM_STMT_SEND_LONG_DATA(0x18, false, MySQLResponseShape.NO_RESPONSE),
    /** Close a prepared statement; no answer. */
    COM_STMT_CLOSE(0x19, false, MySQLResponseShape.NO_RESPONSE),
    /** Reset a prepared statement, discarding stored parameters. */
    COM_STMT_RESET(0x1A, true, MySQLResponseShape.OK),
    /** Enable or disable multi-statement mode; answered with EOF, or OK when EOF is deprecated. */
    COM_SET_OPTION(0x1B, true, MySQLResponseShape.EOF_ONLY),
    /** Fetch rows from a prepared statement cursor. */
    COM_STMT_FETCH(0x1C, true, MySQLResponseShape.RESULTSET),
    /** Internal server packet, never sent by a client. */
    COM_DAEMON(0x1D, false, MySQLResponseShape.UNKNOWN),
    /** Replication: request a binlog stream by GTID. */
    COM_BINLOG_DUMP_GTID(0x1E, true, MySQLResponseShape.STREAM),
    /** Reset the session state without re-authenticating. */
    COM_RESET_CONNECTION(0x1F, true, MySQLResponseShape.OK);

    private final int code;
    private final boolean expectsResponse;
    private final MySQLResponseShape responseShape;

    MySQLCommandType(int code, boolean expectsResponse, MySQLResponseShape responseShape) {
        this.code = code;
        this.expectsResponse = expectsResponse;
        this.responseShape = responseShape;
    }

    public int getCode() {
        return code;
    }

    /**
     * Whether the target database answers this command at all. Commands that draw
     * no response must not start a command cycle (rule 3.6).
     */
    public boolean expectsResponse() {
        return expectsResponse;
    }

    /**
     * The wire shape of this command's response, used by the observation phase
     * machine to pick its branch (rule 3.6).
     */
    public MySQLResponseShape getResponseShape() {
        return responseShape;
    }

    /**
     * Resolves a command code, returning empty for commands the gateway does not
     * need to name.
     */
    public static Optional<MySQLCommandType> fromCode(int code) {
        return Arrays.stream(values())
                .filter(commandType -> commandType.code == code)
                .findFirst();
    }
}
