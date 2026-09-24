package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.mysql.MySqlBackendSessionReset;
import com.whosly.gateway.adapter.postgresql.PostgreSQLBackendSessionReset;
import com.whosly.gateway.adapter.protocol.BackendSessionReset;
import com.whosly.gateway.adapter.stub.OracleProtocolAdapterStub;
import com.whosly.gateway.adapter.stub.SqlServerProtocolAdapterStub;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Extension point: maps {@code gateway.proxy-db-type} → adapter factory and optional
 * {@link BackendSessionReset} supplier.
 *
 * <p><strong>How to add a new database:</strong></p>
 * <ol>
 *   <li>Implement {@link ProtocolAdapter} (typically extend
 *       {@link AbstractProtocolAdapter}) with framing, session, and relay.</li>
 *   <li>Optionally implement {@link BackendSessionReset} for pool wire reset.</li>
 *   <li>Call {@link #register(String, ProtocolAdapterFactory, Supplier)} (or the
 *       overload without reset) — aliases may share one registration.</li>
 *   <li>Document the type in README / STATUS; keep reserved stubs until ready.</li>
 * </ol>
 *
 * <p>Built-in: {@code mysql}, {@code postgresql}/{@code postgres}. Reserved stubs:
 * {@code oracle}, {@code sqlserver}/{@code mssql} — create throws
 * {@link UnsupportedOperationException} with a clear message.</p>
 */
public final class ProtocolAdapterRegistry {

    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    public ProtocolAdapterRegistry() {
    }

    /**
     * Registry preloaded with built-in MySQL / PostgreSQL and reserved stubs.
     */
    public static ProtocolAdapterRegistry withBuiltIns() {
        ProtocolAdapterRegistry registry = new ProtocolAdapterRegistry();
        registry.register("mysql", MySqlProtocolAdapter::new, MySqlBackendSessionReset::new);
        registry.register("postgresql", PostgreSQLProtocolAdapter::new, PostgreSQLBackendSessionReset::new);
        registry.registerAlias("postgres", "postgresql");
        registry.register("oracle", OracleProtocolAdapterStub::unsupported, BackendSessionReset::none);
        registry.register("sqlserver", SqlServerProtocolAdapterStub::unsupported, BackendSessionReset::none);
        registry.registerAlias("mssql", "sqlserver");
        return registry;
    }

    public void register(String dbType, ProtocolAdapterFactory factory) {
        register(dbType, factory, BackendSessionReset::none);
    }

    public void register(String dbType, ProtocolAdapterFactory factory,
                         Supplier<BackendSessionReset> sessionResetSupplier) {
        String key = normalize(dbType);
        Objects.requireNonNull(factory, "factory must not be null");
        Objects.requireNonNull(sessionResetSupplier, "sessionResetSupplier must not be null");
        registrations.put(key, new Registration(factory, sessionResetSupplier));
    }

    /**
     * Points {@code alias} at the same registration as {@code canonical}.
     * Canonical must already be registered.
     */
    public void registerAlias(String alias, String canonical) {
        String canonKey = normalize(canonical);
        Registration registration = registrations.get(canonKey);
        if (registration == null) {
            throw new IllegalArgumentException(
                    "Cannot alias '" + alias + "' to unregistered type '" + canonical + "'");
        }
        registrations.put(normalize(alias), registration);
    }

    public boolean isRegistered(String dbType) {
        return registrations.containsKey(normalize(dbType));
    }

    public Set<String> registeredTypes() {
        return Set.copyOf(registrations.keySet());
    }

    public ProtocolAdapter create(String dbType) {
        Registration registration = require(dbType);
        return registration.factory().create();
    }

    /**
     * Protocol-specific pool reset for {@code dbType}, or {@link BackendSessionReset#none()}
     * when the registration has no wire reset.
     */
    public BackendSessionReset createSessionReset(String dbType) {
        Registration registration = require(dbType);
        BackendSessionReset reset = registration.sessionResetSupplier().get();
        return reset != null ? reset : BackendSessionReset.none();
    }

    public Optional<Registration> find(String dbType) {
        return Optional.ofNullable(registrations.get(normalize(dbType)));
    }

    private Registration require(String dbType) {
        String key = normalize(dbType);
        Registration registration = registrations.get(key);
        if (registration == null) {
            throw new IllegalArgumentException(
                    "Unsupported gateway.proxy-db-type: " + dbType
                            + " (registered: " + registrations.keySet()
                            + "; add via ProtocolAdapterRegistry.register)");
        }
        return registration;
    }

    private static String normalize(String dbType) {
        if (dbType == null || dbType.isBlank()) {
            throw new IllegalArgumentException("dbType must not be blank");
        }
        return dbType.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * One db-type binding: adapter factory + optional session-reset supplier.
     */
    public record Registration(
            ProtocolAdapterFactory factory,
            Supplier<BackendSessionReset> sessionResetSupplier
    ) {
        public Registration {
            Objects.requireNonNull(factory, "factory");
            Objects.requireNonNull(sessionResetSupplier, "sessionResetSupplier");
        }
    }
}
