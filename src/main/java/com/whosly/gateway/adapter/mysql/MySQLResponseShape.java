package com.whosly.gateway.adapter.mysql;

/**
 * The wire shape a MySQL command's response takes.
 *
 * <p>MySQL response framing is not self describing: the same first payload byte
 * means different things depending on the command that was sent. The observation
 * phase machine therefore branches on the shape declared by
 * {@link MySQLCommandType} instead of guessing from the payload (rule 3.6).</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public enum MySQLResponseShape {

    /** A single OK_Packet. */
    OK,

    /** A run of column definitions terminated by EOF, with no column count packet. */
    COLUMN_LIST,

    /**
     * A prepare-ok header carrying the statement id and metadata counts, followed
     * by that many parameter and column definition packets and their terminators.
     */
    PREPARE,

    /**
     * Column count, column definitions, optional EOF, rows, then the terminator.
     * The header may also be an OK_Packet when the statement returns no rows.
     */
    RESULTSET,

    /** A single EOF_Packet, or an OK_Packet when CLIENT_DEPRECATE_EOF is set. */
    EOF_ONLY,

    /** A single packet whose payload is an unmarked string. */
    RAW_STRING,

    /** A response stream that only ends when the connection does. */
    STREAM,

    /** The server sends no packet at all. */
    NO_RESPONSE,

    /**
     * The response shape cannot be inferred, either because the command is not a
     * client command or because it has not been modeled. Observation suspends
     * rather than guessing (rule 2.10).
     */
    UNKNOWN
}
