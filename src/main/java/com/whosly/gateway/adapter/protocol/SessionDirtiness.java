package com.whosly.gateway.adapter.protocol;

/**
 * Session state that would leak into a later client of the same connection.
 *
 * <p>Connection pooling may only reuse a backend connection whose session state
 * is provably clean, or clean it with an explicit reset. Several kinds of state
 * are invisible on the wire (temporary tables, user variables, advisory locks),
 * so they are derived from observed SQL and treated conservatively: whenever the
 * gateway cannot prove a connection is clean, it reports it as dirty
 * (rule 8.3).</p>
 *
 * @param hasPreparedStatements the session holds server-side prepared statements
 * @param hasSessionSettings    the session changed session-scoped settings
 * @param hasTemporaryObjects   the session created a temporary object
 * @param hasUserVariables      the session touched user variables
 * @param hasLocks              the session took a lock
 * @param tooComplexToReset     the session ran something that could not be
 *                              classified, so it must be assumed dirty
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public record SessionDirtiness(
        boolean hasPreparedStatements,
        boolean hasSessionSettings,
        boolean hasTemporaryObjects,
        boolean hasUserVariables,
        boolean hasLocks,
        boolean tooComplexToReset) {

    private static final SessionDirtiness CLEAN =
            new SessionDirtiness(false, false, false, false, false, false);

    /** A session with no observed reusable state. */
    public static SessionDirtiness clean() {
        return CLEAN;
    }

    /**
     * True when nothing observed would leak into another client of this
     * connection. Transaction state is tracked separately, because reuse is
     * additionally forbidden while a transaction is open.
     */
    public boolean isClean() {
        return !hasPreparedStatements
                && !hasSessionSettings
                && !hasTemporaryObjects
                && !hasUserVariables
                && !hasLocks
                && !tooComplexToReset;
    }
}
