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
import java.util.UUID;

/**
 * Persistence for per-instance masking rules (control-plane H2).
 *
 * <p>Cascade: callers must invoke {@link #deleteByInstanceId(String)} when a
 * console instance is removed (see {@code GatewayListenerRuntime#removeInstance}).</p>
 */
@Repository
public class MaskingRuleStore {

    private static final Logger log = LoggerFactory.getLogger(MaskingRuleStore.class);

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS gateway_instance_masking_rule (
              id               VARCHAR(128) PRIMARY KEY,
              instance_id      VARCHAR(128) NOT NULL,
              name             VARCHAR(256) NOT NULL,
              strategy         VARCHAR(32)  NOT NULL,
              priority         INT          NOT NULL DEFAULT 0,
              column_name      VARCHAR(256),
              table_name       VARCHAR(256),
              name_pattern     VARCHAR(512),
              fixed_value      VARCHAR(1024),
              keep_prefix      INT,
              keep_suffix      INT,
              hash_hex_length  INT,
              enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
              created_at       TIMESTAMP    NOT NULL,
              updated_at       TIMESTAMP    NOT NULL
            )
            """;

    private static final String IDX = """
            CREATE INDEX IF NOT EXISTS idx_masking_rule_instance
              ON gateway_instance_masking_rule (instance_id)
            """;

    private static final RowMapper<MaskingRuleRecord> ROW_MAPPER = MaskingRuleStore::mapRow;

    private final JdbcTemplate jdbc;

    public MaskingRuleStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        jdbc.execute(DDL);
        jdbc.execute(IDX);
        log.info("Console control-plane table gateway_instance_masking_rule ready (H2)");
    }

    public List<MaskingRuleRecord> findByInstanceId(String instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        return jdbc.query(
                """
                SELECT id, instance_id, name, strategy, priority,
                       column_name, table_name, name_pattern,
                       fixed_value, keep_prefix, keep_suffix, hash_hex_length,
                       enabled, created_at, updated_at
                FROM gateway_instance_masking_rule
                WHERE instance_id = ?
                ORDER BY priority DESC, name ASC
                """,
                ROW_MAPPER, instanceId);
    }

    public Optional<MaskingRuleRecord> findById(String id) {
        List<MaskingRuleRecord> rows = jdbc.query(
                """
                SELECT id, instance_id, name, strategy, priority,
                       column_name, table_name, name_pattern,
                       fixed_value, keep_prefix, keep_suffix, hash_hex_length,
                       enabled, created_at, updated_at
                FROM gateway_instance_masking_rule
                WHERE id = ?
                """,
                ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    public Optional<MaskingRuleRecord> findByIdAndInstance(String id, String instanceId) {
        List<MaskingRuleRecord> rows = jdbc.query(
                """
                SELECT id, instance_id, name, strategy, priority,
                       column_name, table_name, name_pattern,
                       fixed_value, keep_prefix, keep_suffix, hash_hex_length,
                       enabled, created_at, updated_at
                FROM gateway_instance_masking_rule
                WHERE id = ? AND instance_id = ?
                """,
                ROW_MAPPER, id, instanceId);
        return rows.stream().findFirst();
    }

    public MaskingRuleRecord insert(MaskingRuleRecord row) {
        Objects.requireNonNull(row, "row");
        Instant now = Instant.now();
        String id = hasText(row.id()) ? row.id().trim() : UUID.randomUUID().toString().replace("-", "");
        Instant created = row.createdAt() != null ? row.createdAt() : now;
        Instant updated = row.updatedAt() != null ? row.updatedAt() : now;
        jdbc.update(
                """
                INSERT INTO gateway_instance_masking_rule
                  (id, instance_id, name, strategy, priority,
                   column_name, table_name, name_pattern,
                   fixed_value, keep_prefix, keep_suffix, hash_hex_length,
                   enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                row.instanceId(),
                row.name(),
                row.strategy(),
                row.priority(),
                blankToNull(row.columnName()),
                blankToNull(row.tableName()),
                blankToNull(row.namePattern()),
                row.fixedValue(),
                row.keepPrefix(),
                row.keepSuffix(),
                row.hashHexLength(),
                row.enabled(),
                Timestamp.from(created),
                Timestamp.from(updated));
        return findById(id).orElseThrow(() ->
                new IllegalStateException("Failed to insert masking rule id=" + id));
    }

    public MaskingRuleRecord update(MaskingRuleRecord row) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(row.id(), "row.id");
        Instant updated = Instant.now();
        int n = jdbc.update(
                """
                UPDATE gateway_instance_masking_rule SET
                  name = ?, strategy = ?, priority = ?,
                  column_name = ?, table_name = ?, name_pattern = ?,
                  fixed_value = ?, keep_prefix = ?, keep_suffix = ?, hash_hex_length = ?,
                  enabled = ?, updated_at = ?
                WHERE id = ? AND instance_id = ?
                """,
                row.name(),
                row.strategy(),
                row.priority(),
                blankToNull(row.columnName()),
                blankToNull(row.tableName()),
                blankToNull(row.namePattern()),
                row.fixedValue(),
                row.keepPrefix(),
                row.keepSuffix(),
                row.hashHexLength(),
                row.enabled(),
                Timestamp.from(updated),
                row.id(),
                row.instanceId());
        if (n <= 0) {
            throw new IllegalArgumentException(
                    "Unknown masking rule id=" + row.id() + " for instance=" + row.instanceId());
        }
        return findById(row.id()).orElseThrow();
    }

    /**
     * Replace all rules for an instance in one shot (delete + insert).
     */
    public List<MaskingRuleRecord> replaceAll(String instanceId, List<MaskingRuleRecord> rules) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(rules, "rules");
        deleteByInstanceId(instanceId);
        List<MaskingRuleRecord> saved = new java.util.ArrayList<>();
        for (MaskingRuleRecord rule : rules) {
            MaskingRuleRecord withInstance = new MaskingRuleRecord(
                    rule.id(),
                    instanceId,
                    rule.name(),
                    rule.strategy(),
                    rule.priority(),
                    rule.columnName(),
                    rule.tableName(),
                    rule.namePattern(),
                    rule.fixedValue(),
                    rule.keepPrefix(),
                    rule.keepSuffix(),
                    rule.hashHexLength(),
                    rule.enabled(),
                    rule.createdAt(),
                    rule.updatedAt());
            saved.add(insert(withInstance));
        }
        return List.copyOf(saved);
    }

    public boolean deleteById(String id, String instanceId) {
        return jdbc.update(
                "DELETE FROM gateway_instance_masking_rule WHERE id = ? AND instance_id = ?",
                id, instanceId) > 0;
    }

    /**
     * Count enabled rules whose strategy normalizes to encrypt
     * (encrypt / encryption / cipher).
     */
    public int countEnabledByStrategy(String strategy) {
        Objects.requireNonNull(strategy, "strategy");
        String s = strategy.trim().toLowerCase(java.util.Locale.ROOT);
        boolean encryptFamily = s.equals("encrypt") || s.equals("encryption") || s.equals("cipher");
        Integer n;
        if (encryptFamily) {
            n = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM gateway_instance_masking_rule
                    WHERE enabled = TRUE
                      AND LOWER(strategy) IN ('encrypt', 'encryption', 'cipher')
                    """,
                    Integer.class);
        } else {
            n = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM gateway_instance_masking_rule
                    WHERE enabled = TRUE AND LOWER(strategy) = ?
                    """,
                    Integer.class, s);
        }
        return n != null ? n : 0;
    }

    public int deleteByInstanceId(String instanceId) {
        return jdbc.update(
                "DELETE FROM gateway_instance_masking_rule WHERE instance_id = ?",
                instanceId);
    }

    private static MaskingRuleRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        Integer keepPrefix = (Integer) rs.getObject("keep_prefix");
        Integer keepSuffix = (Integer) rs.getObject("keep_suffix");
        Integer hashHex = (Integer) rs.getObject("hash_hex_length");
        return new MaskingRuleRecord(
                rs.getString("id"),
                rs.getString("instance_id"),
                rs.getString("name"),
                rs.getString("strategy"),
                rs.getInt("priority"),
                rs.getString("column_name"),
                rs.getString("table_name"),
                rs.getString("name_pattern"),
                rs.getString("fixed_value"),
                keepPrefix,
                keepSuffix,
                hashHex,
                rs.getBoolean("enabled"),
                created != null ? created.toInstant() : Instant.EPOCH,
                updated != null ? updated.toInstant() : Instant.EPOCH
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
