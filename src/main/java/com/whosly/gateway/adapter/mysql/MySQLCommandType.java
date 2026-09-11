package com.whosly.gateway.adapter.mysql;

import java.util.Arrays;
import java.util.Optional;

/**
 * MySQL command phase command codes.
 *
 * <p>A client sends one of these as the first payload byte of a command packet
 * once the connection phase has finished. The gateway only observes the code:
 * SQL text is extracted for audit, while the command packet itself is forwarded
 * verbatim to the target database.</p>
 *
 * @see <a href="https://dev.mysql.com/doc/dev/mysql-server/latest/page_protocol_command_phase.html">
 *     MySQL Command Phase</a>
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public enum MySQLCommandType {

    /** Internal server state, never sent by a client. */
    COM_SLEEP(0x00),
    /** Client closes the connection; the server tears the session down. */
    COM_QUIT(0x01),
    /** Switch the session default database. */
    COM_INIT_DB(0x02),
    /** Text protocol SQL query; the main audited command. */
    COM_QUERY(0x03),
    /** List a table's columns (deprecated). */
    COM_FIELD_LIST(0x04),
    /** Create a database (deprecated). */
    COM_CREATE_DB(0x05),
    /** Drop a database (deprecated). */
    COM_DROP_DB(0x06),
    /** Flush tables, logs, caches or privileges. */
    COM_REFRESH(0x07),
    /** Shut the server down; requires the SHUTDOWN privilege. */
    COM_SHUTDOWN(0x08),
    /** Return a short status string for monitoring. */
    COM_STATISTICS(0x09),
    /** Equivalent to SHOW PROCESSLIST (deprecated). */
    COM_PROCESS_INFO(0x0A),
    /** Internal server packet. */
    COM_CONNECT(0x0B),
    /** Kill another connection (deprecated). */
    COM_PROCESS_KILL(0x0C),
    /** Dump internal debug information. */
    COM_DEBUG(0x0D),
    /** Heartbeat; the server replies with an OK packet. */
    COM_PING(0x0E),
    /** Internal server packet. */
    COM_TIME(0x0F),
    /** Deprecated delayed-insert command. */
    COM_DELAYED_INSERT(0x10),
    /** Re-authenticate and switch user on the same connection. */
    COM_CHANGE_USER(0x11),
    /** Replication: request a binlog stream from a given position. */
    COM_BINLOG_DUMP(0x12),
    /** Replication: request a table dump (deprecated). */
    COM_TABLE_DUMP(0x13),
    /** Internal server packet. */
    COM_CONNECT_OUT(0x14),
    /** Replication: replica registration (deprecated). */
    COM_REGISTER_SLAVE(0x15),
    /** Prepare a statement; returns a statement id plus parameter/column metadata. */
    COM_STMT_PREPARE(0x16),
    /** Execute a prepared statement. */
    COM_STMT_EXECUTE(0x17),
    /** Send one parameter value of a prepared statement before execute. */
    COM_STMT_SEND_LONG_DATA(0x18),
    /** Close a prepared statement. */
    COM_STMT_CLOSE(0x19),
    /** Reset a prepared statement, discarding stored parameters. */
    COM_STMT_RESET(0x1A),
    /** Enable or disable multi-statement mode. */
    COM_SET_OPTION(0x1B),
    /** Fetch rows from a prepared statement cursor. */
    COM_STMT_FETCH(0x1C),
    /** Internal server packet. */
    COM_DAEMON(0x1D),
    /** Replication: request a binlog stream by GTID. */
    COM_BINLOG_DUMP_GTID(0x1E),
    /** Reset the session state without re-authenticating. */
    COM_RESET_CONNECTION(0x1F);

    private final int code;

    MySQLCommandType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
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
