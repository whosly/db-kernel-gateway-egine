package com.whosly.gateway.parser;

/**
 * Session-level effects an observed SQL statement can have.
 *
 * <p>These effects drive the session dirtiness model: a statement that changes
 * session state, creates temporary objects, uses user variables or takes locks
 * makes the connection unsafe to hand to another client without a reset
 * (see {@code docs/rules/database-protocol-rules.md} §8.3).</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public enum StatementEffect {

    /** Reads only: SELECT, SHOW, EXPLAIN and similar. */
    READ,

    /** Modifies data: INSERT, UPDATE, DELETE, REPLACE, TRUNCATE and similar. */
    WRITE,

    /** Modifies schema: CREATE, ALTER, DROP, RENAME and similar. */
    DDL,

    /** Opens, commits or rolls back a transaction. */
    TRANSACTION_CONTROL,

    /** Changes session-scoped settings: SET, RESET, DISCARD, USE, LISTEN. */
    SESSION_SETTING,

    /** Creates a temporary object, such as a temporary table. */
    TEMPORARY_OBJECT,

    /** Reads or writes a session-scoped user variable. */
    USER_VARIABLE,

    /** Takes a session- or object-level lock. */
    LOCK,

    /**
     * The statement could not be classified. Treated as the most conservative
     * case: the session is marked as not reusable without a reset.
     */
    UNKNOWN
}
