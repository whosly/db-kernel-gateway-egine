package com.whosly.gateway.console.persist;

import com.whosly.gateway.console.security.ConsoleSecretCipher;
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
 * <p>{@code target_password} is sealed with {@link ConsoleSecretCipher} when a master key
 * is configured ({@code enc:v1:}…); otherwise lab plaintext with a one-time WARN.
 * When {@code gateway.console.require-secret-encryption=true} and the key is missing,
 * {@link ConsoleSecretCipher#sealForStorage} throws (no silent plaintext).
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
              target_password   VARCHAR(2048),
              source            VARCHAR(32)  NOT NULL DEFAULT 'console',
              created_at        TIMESTAMP    NOT NULL,
              updated_at        TIMESTAMP    NOT NULL
            )
            """;

    private final JdbcTemplate jdbc;
    private final ConsoleSecretCipher cipher;
    private final RowMapper<ConsoleInstanceRecord> rowMapper;

    public ConsoleInstanceStore(JdbcTemplate jdbc, ConsoleSecretCipher cipher) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.rowMapper = this::mapRow;
        jdbc.execute(DDL);
        log.info("Console control-plane table gateway_instance ready (encrypt={})",
                cipher.isMasterKeyConfigured());
    }

    /** Test helper: lab mode (no master key). */
    public ConsoleInstanceStore(JdbcTemplate jdbc) {
        this(jdbc, ConsoleSecretCipher.fromBase64MasterKey(null));
    }

    public List<ConsoleInstanceRecord> findAll() {
        return jdbc.query(
                "SELECT id, name, db_type, listen_host, listen_port, enabled, "
                        + "target_host, target_port, target_database, target_username, target_password, "
                        + "created_at, updated_at FROM gateway_instance ORDER BY created_at ASC",
                rowMapper);
    }

    public Optional<ConsoleInstanceRecord> findById(String id) {
        List<ConsoleInstanceRecord> rows = jdbc.query(
                "SELECT id, name, db_type, listen_host, listen_port, enabled, "
                        + "target_host, target_port, target_database, target_username, target_password, "
                        + "created_at, updated_at FROM gateway_instance WHERE id = ?",
                rowMapper, id);
        return rows.stream().findFirst();
    }

    public void upsert(ConsoleInstanceRecord row) {
        Objects.requireNonNull(row, "row");
        Instant now = Instant.now();
        Instant created = row.createdAt() != null ? row.createdAt() : now;
        Instant updated = row.updatedAt() != null ? row.updatedAt() : now;
        String sealedPassword = cipher.sealForStorage(row.targetPassword());
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
                sealedPassword,
                Timestamp.from(created),
                Timestamp.from(updated));
        if (updatedRows <= 0) {
            throw new IllegalStateException("Failed to upsert gateway_instance id=" + row.id());
        }
    }

    public boolean deleteById(String id) {
        return jdbc.update("DELETE FROM gateway_instance WHERE id = ?", id) > 0;
    }

    /**
     * Raw sealed password as stored (for tests). Prefer {@link #findById} which decrypts.
     */
    public Optional<String> findSealedPassword(String id) {
        List<String> rows = jdbc.query(
                "SELECT target_password FROM gateway_instance WHERE id = ?",
                (rs, n) -> rs.getString(1),
                id);
        return rows.stream().findFirst();
    }

    private ConsoleInstanceRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        String storedPassword = rs.getString("target_password");
        String plaintextPassword = cipher.openFromStorage(storedPassword);
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
                plaintextPassword,
                created != null ? created.toInstant() : Instant.EPOCH,
                updated != null ? updated.toInstant() : Instant.EPOCH
        );
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
