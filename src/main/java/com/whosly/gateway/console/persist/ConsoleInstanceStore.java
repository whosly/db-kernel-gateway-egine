package com.whosly.gateway.console.persist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistence for console-managed gateway instances (control-plane H2).
 *
 * <p><strong>WARNING:</strong> {@code target_password} is stored in the clear for lab MVP.
 * Production must use a secret manager / envelope encryption (see CONSOLE_ARCHITECTURE Phase B).
 * This DB is <em>not</em> the proxied business database.</p>
 */
@Repository
public class ConsoleInstanceStore {

    private static final Logger log = LoggerFactory.getLogger(ConsoleInstanceStore.class);

    public static final String SOURCE_CONSOLE = "console";

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS gateway_instance (
              id                VARCHAR(128) PRIMARY KEY,
              name              VARCHAR(256) NOT NULL,
              db_type           VARCHAR(64)  NOT NULL,
              listen_host       VARCHAR(128) NOT NULL,
              listen_port       INT          NOT NULL,
              enabled           BOOLEAN      NOT NULL,
              target_host       VARCHAR(256) NOT NULL,
              target_port       INT          NOT NULL,
              target_database   VARCHAR(256),
              target_username   VARCHAR(256),
              target_password   VARCHAR(1024),
              source            VARCHAR(32)  NOT NULL DEFAULT 'console',
              created_at        TIMESTAMP    NOT NULL,
              updated_at        TIMESTAMP    NOT NULL
            )
            """;

    private static final RowMapper<ConsoleInstanceRecord> ROW_MAPPER = ConsoleInstanceStore::mapRow;

    private final JdbcTemplate jdbc;

    public ConsoleInstanceStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        jdbc.execute(DDL);
        log.info("Console control-plane table gateway_instance ready (H2)");
    }

    public List<ConsoleInstanceRecord> findAll() {
        return jdbc.query(
                "SELECT id, name, db_type, listen_host, listen_port, enabled, "
                        + "target_host, target_port, target_database, target_username, target_password, "
                        + "created_at, updated_at FROM gateway_instance ORDER BY created_at ASC",
                ROW_MAPPER);
    }

    public Optional<ConsoleInstanceRecord> findById(String id) {
        List<ConsoleInstanceRecord> rows = jdbc.query(
                "SELECT id, name, db_type, listen_host, listen_port, enabled, "
                        + "target_host, target_port, target_database, target_username, target_password, "
                        + "created_at, updated_at FROM gateway_instance WHERE id = ?",
                ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    public void upsert(ConsoleInstanceRecord row) {
        Objects.requireNonNull(row, "row");
        Instant now = Instant.now();
        Instant created = row.createdAt() != null ? row.createdAt() : now;
        Instant updated = row.updatedAt() != null ? row.updatedAt() : now;
        int updatedRows = jdbc.update(
                """
                MERGE INTO gateway_instance
                  (id, name, db_type, listen_host, listen_port, enabled,
                   target_host, target_port, target_database, target_username, target_password,
                   source, created_at, updated_at)
                KEY (id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'console', ?, ?)
                """,
                row.id(),
                row.name(),
                row.dbType(),
                row.listenHost(),
                row.listenPort(),
                row.enabled(),
                nullToEmpty(row.targetHost()),
                row.targetPort(),
                row.targetDatabase(),
                row.targetUsername(),
                row.targetPassword(),
                Timestamp.from(created),
                Timestamp.from(updated));
        if (updatedRows <= 0) {
            throw new IllegalStateException("Failed to upsert gateway_instance id=" + row.id());
        }
    }

    public boolean deleteById(String id) {
        return jdbc.update("DELETE FROM gateway_instance WHERE id = ?", id) > 0;
    }

    private static ConsoleInstanceRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new ConsoleInstanceRecord(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("db_type"),
                rs.getString("listen_host"),
                rs.getInt("listen_port"),
                rs.getBoolean("enabled"),
                rs.getString("target_host"),
                rs.getInt("target_port"),
                rs.getString("target_database"),
                rs.getString("target_username"),
                rs.getString("target_password"),
                created != null ? created.toInstant() : Instant.EPOCH,
                updated != null ? updated.toInstant() : Instant.EPOCH
        );
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
