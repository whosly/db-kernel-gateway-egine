package com.whosly.gateway.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JDBC destination for audit records (independent sink DB — never the proxied business DB).
 *
 * <p>Parses {@code session=} / {@code sequence=} from spool payload lines so the
 * {@code PRIMARY KEY (session_id, record_sequence)} matches {@code docs/sql/audit-sink-schema.sql}.
 * Duplicate keys (at-least-once ship replay) are treated as success.</p>
 *
 * <p>For H2 URLs, {@link #ensureSchema()} creates the table if absent (lab path).
 * MySQL/PostgreSQL expect the table to already exist (or use dialect CREATE IF NOT EXISTS).</p>
 */
public final class JdbcAuditDestination implements AuditDestination {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditDestination.class);

    public enum Dialect {
        H2, MYSQL, POSTGRES, GENERIC
    }

    private final String url;
    private final String username;
    private final String password;
    private final String table;
    private final Dialect dialect;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);
    private final AtomicLong rowsInserted = new AtomicLong();
    private final AtomicLong rowsSkippedDuplicate = new AtomicLong();
    private final AtomicReference<Instant> lastShipAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public JdbcAuditDestination(String url, String username, String password, String table) {
        this.url = Objects.requireNonNull(url, "url");
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        String t = (table == null || table.isBlank()) ? "gateway_audit_record" : table.trim();
        if (!t.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Illegal audit jdbc table name: " + t);
        }
        this.table = t;
        this.dialect = detectDialect(url);
    }

    public Dialect dialect() {
        return dialect;
    }

    public String table() {
        return table;
    }

    public String urlRedacted() {
        // Never echo password; strip userinfo if present
        String u = url;
        int at = u.indexOf('@');
        if (at > 0 && u.startsWith("jdbc:")) {
            int schemeEnd = u.indexOf("://");
            if (schemeEnd > 0 && at > schemeEnd) {
                return u.substring(0, schemeEnd + 3) + "***@" + u.substring(at + 1);
            }
        }
        return u;
    }

    public long rowsInserted() {
        return rowsInserted.get();
    }

    public long rowsSkippedDuplicate() {
        return rowsSkippedDuplicate.get();
    }

    public Instant lastShipAt() {
        return lastShipAt.get();
    }

    public String lastError() {
        return lastError.get();
    }

    /** Best-effort COUNT(*); null on failure (does not throw). */
    public Long countRows() {
        try {
            ensureSchema();
            String sql = "SELECT COUNT(*) FROM " + table;
            try (Connection c = open();
                 PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (Exception e) {
            lastError.set(shortError(e));
            log.debug("audit jdbc count failed: {}", e.toString());
        }
        return null;
    }

    /**
     * Create sink table if absent (H2 lab / MySQL / PG IF NOT EXISTS).
     * Safe to call repeatedly.
     */
    public void ensureSchema() throws SQLException {
        if (schemaReady.get()) {
            return;
        }
        synchronized (this) {
            if (schemaReady.get()) {
                return;
            }
            String ddl = createTableDdl();
            try (Connection c = open(); Statement st = c.createStatement()) {
                st.execute(ddl);
            }
            schemaReady.set(true);
            log.info("Audit JDBC sink schema ready dialect={} table={}", dialect, table);
        }
    }

    @Override
    public void writeBatch(List<String> records) throws IOException {
        if (records == null || records.isEmpty()) {
            return;
        }
        try {
            ensureSchema();
            int inserted = 0;
            int skipped = 0;
            try (Connection connection = open()) {
                connection.setAutoCommit(false);
                for (String record : records) {
                    if (writeOne(connection, record)) {
                        inserted++;
                    } else {
                        skipped++;
                    }
                }
                connection.commit();
            }
            rowsInserted.addAndGet(inserted);
            rowsSkippedDuplicate.addAndGet(skipped);
            lastShipAt.set(Instant.now());
            lastError.set(null);
        } catch (SQLException e) {
            lastError.set(shortError(e));
            throw new IOException("Failed to write audit batch to JDBC destination", e);
        }
    }

    /** @return true if inserted (or merged), false if duplicate skipped */
    private boolean writeOne(Connection connection, String record) throws SQLException {
        ParsedKey key = parseKey(record);
        String sql = insertSql();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.sessionId());
            statement.setLong(2, key.sequence());
            statement.setString(3, record == null ? "" : record);
            int updated = statement.executeUpdate();
            // INSERT IGNORE / ON CONFLICT DO NOTHING return 0 when skipped
            if (dialect == Dialect.MYSQL || dialect == Dialect.POSTGRES) {
                return updated > 0;
            }
            return true; // H2 / generic INSERT (duplicates raise SQLException)
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                return false;
            }
            throw e;
        }
    }

    private String insertSql() {
        // H2/GENERIC: plain INSERT; duplicates caught via SQLState 23505 in writeOne.
        // MySQL/PG: dialect-native ignore-on-conflict so batch replay stays at-least-once → exactly-once in table.
        return switch (dialect) {
            case MYSQL -> "INSERT IGNORE INTO " + table
                    + " (session_id, record_sequence, payload) VALUES (?, ?, ?)";
            case POSTGRES -> "INSERT INTO " + table
                    + " (session_id, record_sequence, payload) VALUES (?, ?, ?) ON CONFLICT DO NOTHING";
            default -> "INSERT INTO " + table
                    + " (session_id, record_sequence, payload) VALUES (?, ?, ?)";
        };
    }

    private String createTableDdl() {
        return switch (dialect) {
            case H2 -> "CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "session_id VARCHAR(128) NOT NULL, "
                    + "record_sequence BIGINT NOT NULL, "
                    + "payload CLOB NOT NULL, "
                    + "recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (session_id, record_sequence))";
            case MYSQL -> "CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "session_id VARCHAR(128) NOT NULL, "
                    + "record_sequence BIGINT NOT NULL, "
                    + "payload TEXT NOT NULL, "
                    + "recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                    + "PRIMARY KEY (session_id, record_sequence)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
            case POSTGRES -> "CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "session_id VARCHAR(128) NOT NULL, "
                    + "record_sequence BIGINT NOT NULL, "
                    + "payload TEXT NOT NULL, "
                    + "recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(), "
                    + "PRIMARY KEY (session_id, record_sequence))";
            default -> "CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "session_id VARCHAR(128) NOT NULL, "
                    + "record_sequence BIGINT NOT NULL, "
                    + "payload CLOB NOT NULL, "
                    + "PRIMARY KEY (session_id, record_sequence))";
        };
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    static Dialect detectDialect(String jdbcUrl) {
        String u = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        if (u.startsWith("jdbc:h2:")) {
            return Dialect.H2;
        }
        if (u.startsWith("jdbc:mysql:") || u.startsWith("jdbc:mariadb:")) {
            return Dialect.MYSQL;
        }
        if (u.startsWith("jdbc:postgresql:") || u.startsWith("jdbc:pgsql:")) {
            return Dialect.POSTGRES;
        }
        return Dialect.GENERIC;
    }

    static ParsedKey parseKey(String record) {
        AuditSpoolReader.ParsedRecord parsed = AuditSpoolReader.parse(record, "jdbc", 0);
        String session = parsed.sessionId();
        if (session == null || session.isBlank()) {
            session = "unknown";
        }
        long seq;
        if (parsed.sequence() != null) {
            seq = parsed.sequence();
        } else {
            // Degenerate payload without sequence — still ship with unique-ish key
            seq = Math.floorMod(Objects.hashCode(record), Integer.MAX_VALUE);
        }
        return new ParsedKey(session, seq);
    }

    static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        if ("23505".equals(state)) { // H2 / PG
            return true;
        }
        if ("23000".equals(state)) { // MySQL often
            return true;
        }
        int code = e.getErrorCode();
        // MySQL ER_DUP_ENTRY = 1062
        if (code == 1062) {
            return true;
        }
        String msg = e.getMessage();
        if (msg != null) {
            String m = msg.toLowerCase(Locale.ROOT);
            return m.contains("unique") || m.contains("duplicate") || m.contains("primary key");
        }
        return false;
    }

    private static String shortError(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getClass().getSimpleName();
        if (cur.getMessage() != null && !cur.getMessage().isBlank()) {
            String m = cur.getMessage().replaceAll("(?i)password=[^\\s,;]+", "password=***");
            if (m.length() > 180) {
                m = m.substring(0, 180) + "…";
            }
            msg = msg + ": " + m;
        }
        return msg;
    }

    record ParsedKey(String sessionId, long sequence) {
    }
}
