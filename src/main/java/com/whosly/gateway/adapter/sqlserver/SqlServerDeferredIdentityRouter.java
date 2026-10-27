package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.BackendProvider;
import com.whosly.gateway.adapter.protocol.GatewayErrorMapping;
import com.whosly.gateway.adapter.protocol.GatewayException;
import com.whosly.gateway.adapter.protocol.RoutingBackendProvider;
import com.whosly.gateway.adapter.protocol.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Objects;

/**
 * SQL Server first-connect identity routing via deferred Login7 rebind.
 *
 * <p>TDS is client-first but PreLogin carries no user/database. Bootstrap always
 * uses {@code acquire(empty)}. After a <em>real</em> PreLogin round-trip with the
 * bootstrap backend:</p>
 * <ul>
 *   <li>If ENCRYPTION will hide Login7 — stay on bootstrap (empty identity),
 *       like MySQL SSLRequest.</li>
 *   <li>Else peek Login7; if {@link RoutingBackendProvider#selectsFallback} —
 *       replay Login7 wire bytes; path stays transparent on bootstrap.</li>
 *   <li>Else — release bootstrap, {@code acquire(context)}, silently complete
 *       PreLogin with the selected backend (client already has bootstrap's
 *       PreLogin response), forward Login7, then transparent duplex.</li>
 * </ul>
 *
 * <p><b>Tradeoff:</b> On the mismatch path the client saw bootstrap PreLogin
 * options while Login7 authenticates against the selected backend's silent
 * PreLogin. No forged PreLogin is sent to the client. Encryption-before-Login7
 * aborts deferred routing.</p>
 */
public final class SqlServerDeferredIdentityRouter {

    private static final Logger log = LoggerFactory.getLogger(SqlServerDeferredIdentityRouter.class);
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;

    private SqlServerDeferredIdentityRouter() {
    }

    /**
     * @param clientToBackendPrefix Login7 (and only Login7) wire bytes to replay
     *                              on the match path; empty after rebind (already written)
     * @param rebound               true when bootstrap was released and a matched backend used
     */
    public record Result(Socket backendSocket, RoutingContext context, byte[] clientToBackendPrefix,
                         boolean rebound) {
        public Result {
            Objects.requireNonNull(backendSocket, "backendSocket");
            Objects.requireNonNull(context, "context");
            clientToBackendPrefix = clientToBackendPrefix == null ? new byte[0] : clientToBackendPrefix;
        }
    }

    @FunctionalInterface
    public interface BootstrapReleaser {
        void release(Socket bootstrap) throws IOException;
    }

    public static Result route(Socket client, Socket bootstrapBackend, BackendProvider backendProvider,
                               BootstrapReleaser releaseBootstrap) throws IOException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(bootstrapBackend, "bootstrapBackend");
        Objects.requireNonNull(backendProvider, "backendProvider");
        Objects.requireNonNull(releaseBootstrap, "releaseBootstrap");

        int prevClientTimeout = client.getSoTimeout();
        int prevBackendTimeout = bootstrapBackend.getSoTimeout();
        try {
            client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            bootstrapBackend.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

            TdsLogicalMessage clientPreLogin = TdsLogicalMessage.read(client.getInputStream());
            if (clientPreLogin.type() != TdsPacketType.PRELOGIN.getCode()) {
                log.debug("SQL Server deferred routing: first client message type 0x{} — transparent",
                        Integer.toHexString(clientPreLogin.type()));
                return new Result(bootstrapBackend, RoutingContext.empty(), clientPreLogin.wireBytes(), false);
            }
            clientPreLogin.writeTo(bootstrapBackend.getOutputStream());

            TdsLogicalMessage serverPreLogin = TdsLogicalMessage.read(bootstrapBackend.getInputStream());
            serverPreLogin.writeTo(client.getOutputStream());

            if (TdsPreLoginOptions.requiresEncryptionBeforeLogin7(
                    clientPreLogin.payload(), serverPreLogin.payload())) {
                log.info("SQL Server deferred routing: PreLogin ENCRYPTION — stay on bootstrap (no cleartext Login7)");
                return new Result(bootstrapBackend, RoutingContext.empty(), new byte[0], false);
            }

            TdsLogicalMessage login7 = TdsLogicalMessage.read(client.getInputStream());
            if (login7.type() != TdsPacketType.TDS7_LOGIN.getCode()) {
                log.debug("SQL Server deferred routing: expected Login7, got 0x{} — transparent",
                        Integer.toHexString(login7.type()));
                return new Result(bootstrapBackend, RoutingContext.empty(), login7.wireBytes(), false);
            }

            RoutingContext identity = SqlServerLogin7Routing.fromLogin7Payload(login7.payload());
            if (!(backendProvider instanceof RoutingBackendProvider routing)
                    || routing.selectsFallback(identity)) {
                log.debug("SQL Server deferred routing: identity {} uses bootstrap/fallback", identity);
                return new Result(bootstrapBackend, identity, login7.wireBytes(), false);
            }

            log.info("SQL Server deferred routing: identity {} mismatches bootstrap — silent PreLogin rebind",
                    identity);
            releaseBootstrap.release(bootstrapBackend);
            Socket selected;
            try {
                selected = routing.acquire(identity);
            } catch (IOException e) {
                throw new GatewayException(GatewayErrorMapping.TARGET_UNAVAILABLE, e);
            }
            try {
                selected.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                rebindViaSilentPreLogin(clientPreLogin, login7, selected);
                return new Result(selected, identity, new byte[0], true);
            } catch (IOException | RuntimeException e) {
                closeQuietly(selected);
                throw e;
            }
        } catch (SocketTimeoutException e) {
            throw new GatewayException(GatewayErrorMapping.PROTOCOL_VIOLATION, e);
        } finally {
            restoreTimeout(client, prevClientTimeout);
            try {
                if (!bootstrapBackend.isClosed()) {
                    restoreTimeout(bootstrapBackend, prevBackendTimeout);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Completes PreLogin with the selected backend using the client's original
     * PreLogin request (response discarded — client already has bootstrap's),
     * then forwards Login7 wire bytes unchanged.
     */
    private static void rebindViaSilentPreLogin(TdsLogicalMessage clientPreLogin,
                                                TdsLogicalMessage login7,
                                                Socket selected) throws IOException {
        clientPreLogin.writeTo(selected.getOutputStream());
        TdsLogicalMessage selectedPreLogin = TdsLogicalMessage.read(selected.getInputStream());
        if (selectedPreLogin.type() != TdsPacketType.PRELOGIN.getCode()
                && selectedPreLogin.type() != TdsPacketType.TABULAR_RESULT.getCode()) {
            // Some stacks answer PreLogin as type 0x04 in odd cases; still consume.
            log.debug("SQL Server deferred routing: selected PreLogin type 0x{}",
                    Integer.toHexString(selectedPreLogin.type()));
        }
        if (TdsPreLoginOptions.requiresEncryptionBeforeLogin7(
                clientPreLogin.payload(), selectedPreLogin.payload())) {
            throw new GatewayException(GatewayErrorMapping.TARGET_HANDSHAKE_FAILED,
                    new IOException("selected backend requires encryption after PreLogin; cannot rebind Login7"));
        }
        login7.writeTo(selected.getOutputStream());
        log.info("SQL Server deferred routing: rebound Login7 to selected backend");
    }

    private static void restoreTimeout(Socket socket, int timeout) {
        try {
            socket.setSoTimeout(timeout);
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
