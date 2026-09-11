package com.whosly.gateway.adapter.postgresql;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Associates PostgreSQL cancel keys with the session they target.
 *
 * <p>{@code BackendKeyData} carries the (process id, secret key) pair a client
 * must echo in a {@code CancelRequest} to cancel that session's running query.
 * A cancel request arrives on its own short-lived connection, so the gateway
 * keeps this index to correlate the two, as rule 4.10 requires.</p>
 *
 * <p>The index is observation state only: the gateway forwards both the original
 * session traffic and the cancel request untouched, never issues a cancel of its
 * own and never rewrites key material.</p>
 *
 * <p>Both values are secret material. They are held in memory only, never
 * logged, and dropped as soon as the session they belong to ends.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class PostgreSQLCancelKeyRegistry {

    private final Map<CancelKey, String> sessionIdsByKey = new ConcurrentHashMap<>();

    /**
     * Indexes the cancel key a session received in {@code BackendKeyData}.
     *
     * @param sessionId the session the key belongs to
     * @param processId backend process id from {@code BackendKeyData}
     * @param secretKey cancel secret from {@code BackendKeyData}
     */
    public void register(String sessionId, int processId, int secretKey) {
        if (sessionId == null) {
            return;
        }
        sessionIdsByKey.put(new CancelKey(processId, secretKey), sessionId);
    }

    /**
     * Finds the session an observed {@code CancelRequest} targets.
     *
     * @return the session id, or empty when the key matches no active session
     */
    public Optional<String> findTargetSessionId(int processId, int secretKey) {
        return Optional.ofNullable(sessionIdsByKey.get(new CancelKey(processId, secretKey)));
    }

    /**
     * Drops every key registered for a session. Called when that session ends so
     * a later cancel request cannot be matched against a closed session.
     */
    public void unregister(String sessionId) {
        if (sessionId == null) {
            return;
        }
        sessionIdsByKey.values().removeIf(sessionId::equals);
    }

    /** Number of indexed cancel keys. */
    public int size() {
        return sessionIdsByKey.size();
    }

    private record CancelKey(int processId, int secretKey) {
    }
}
