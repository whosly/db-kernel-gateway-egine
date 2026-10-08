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
 * Control-plane H2 store for alert thresholds ({@code gateway_alert_threshold}).
 * Evaluation state ({@code last_*} columns) survives restart; live firing is re-derived.
 */
@Repository
public class AlertThresholdStore {

    private static final Logger log = LoggerFactory.getLogger(AlertThresholdStore.class);

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS gateway_alert_threshold (
              id               VARCHAR(128)  PRIMARY KEY,
              name             VARCHAR(256)  NOT NULL,
              metric_key       VARCHAR(64)   NOT NULL,
              comparator       VARCHAR(8)    NOT NULL,
              threshold_value  DOUBLE        NOT NULL,
              window_seconds   INT,
              instance_id      VARCHAR(128),
              enabled          BOOLEAN       NOT NULL DEFAULT TRUE,
              severity         VARCHAR(16)   NOT NULL DEFAULT 'WARN',
              last_fired_at    TIMESTAMP,
              last_value       DOUBLE,
              last_firing      BOOLEAN       NOT NULL DEFAULT FALSE,
              created_at       TIMESTAMP     NOT NULL,
              updated_at       TIMESTAMP     NOT NULL
            )
            """;

    private static final String IDX = """
            CREATE INDEX IF NOT EXISTS idx_alert_threshold_enabled
              ON gateway_alert_threshold (enabled)
            """;

    private static final RowMapper<AlertThresholdRecord> ROW_MAPPER = AlertThresholdStore::mapRow;

    private final JdbcTemplate jdbc;

    public AlertThresholdStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        jdbc.execute(DDL);
        jdbc.execute(IDX);
        log.info("Console control-plane table gateway_alert_threshold ready (H2)");
    }

    public List<AlertThresholdRecord> findAll() {
        return jdbc.query(
                """
                SELECT id, name, metric_key, comparator, threshold_value, window_seconds,
                       instance_id, enabled, severity, last_fired_at, last_value, last_firing,
                       created_at, updated_at
                FROM gateway_alert_threshold
                ORDER BY CASE severity
                           WHEN 'CRITICAL' THEN 0
                           WHEN 'WARN' THEN 1
                           ELSE 2 END,
                         name ASC, id ASC
                """,
                ROW_MAPPER);
    }

    public List<AlertThresholdRecord> findEnabled() {
        return jdbc.query(
                """
                SELECT id, name, metric_key, comparator, threshold_value, window_seconds,
                       instance_id, enabled, severity, last_fired_at, last_value, last_firing,
                       created_at, updated_at
                FROM gateway_alert_threshold
                WHERE enabled = TRUE
                ORDER BY CASE severity
                           WHEN 'CRITICAL' THEN 0
                           WHEN 'WARN' THEN 1
                           ELSE 2 END,
                         name ASC, id ASC
                """,
                ROW_MAPPER);
    }

    public Optional<AlertThresholdRecord> findById(String id) {
        List<AlertThresholdRecord> rows = jdbc.query(
                """
                SELECT id, name, metric_key, comparator, threshold_value, window_seconds,
                       instance_id, enabled, severity, last_fired_at, last_value, last_firing,
                       created_at, updated_at
                FROM gateway_alert_threshold
                WHERE id = ?
                """,
                ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    public AlertThresholdRecord insert(AlertThresholdRecord row) {
        Objects.requireNonNull(row, "row");
        Instant now = Instant.now();
        String id = hasText(row.id()) ? row.id().trim() : UUID.randomUUID().toString().replace("-", "");
        Instant created = row.createdAt() != null ? row.createdAt() : now;
        Instant updated = row.updatedAt() != null ? row.updatedAt() : now;
        jdbc.update(
                """
                INSERT INTO gateway_alert_threshold
                  (id, name, metric_key, comparator, threshold_value, window_seconds,
                   instance_id, enabled, severity, last_fired_at, last_value, last_firing,
                   created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                row.name(),
                row.metricKey(),
                row.comparator(),
                row.thresholdValue(),
                row.windowSeconds(),
                blankToNull(row.instanceId()),
                row.enabled(),
                row.severity(),
                toTs(row.lastFiredAt()),
                row.lastValue(),
                row.lastFiring(),
                Timestamp.from(created),
                Timestamp.from(updated));
        return findById(id).orElseThrow();
    }

    public AlertThresholdRecord update(AlertThresholdRecord row) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(row.id(), "id");
        Instant now = Instant.now();
        int n = jdbc.update(
                """
                UPDATE gateway_alert_threshold
                SET name = ?, metric_key = ?, comparator = ?, threshold_value = ?,
                    window_seconds = ?, instance_id = ?, enabled = ?, severity = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                row.name(),
                row.metricKey(),
                row.comparator(),
                row.thresholdValue(),
                row.windowSeconds(),
                blankToNull(row.instanceId()),
                row.enabled(),
                row.severity(),
                Timestamp.from(now),
                row.id());
        if (n == 0) {
            throw new IllegalArgumentException("告警阈值不存在: " + row.id());
        }
        return findById(row.id()).orElseThrow();
    }

    public void updateEvalState(String id, boolean firing, double value, Instant firedAt) {
        jdbc.update(
                """
                UPDATE gateway_alert_threshold
                SET last_firing = ?, last_value = ?, last_fired_at = COALESCE(?, last_fired_at),
                    updated_at = ?
                WHERE id = ?
                """,
                firing,
                value,
                firing && firedAt != null ? Timestamp.from(firedAt) : null,
                Timestamp.from(Instant.now()),
                id);
    }

    public boolean delete(String id) {
        return jdbc.update("DELETE FROM gateway_alert_threshold WHERE id = ?", id) > 0;
    }

    private static AlertThresholdRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AlertThresholdRecord(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("metric_key"),
                rs.getString("comparator"),
                rs.getDouble("threshold_value"),
                (Integer) rs.getObject("window_seconds"),
                rs.getString("instance_id"),
                rs.getBoolean("enabled"),
                rs.getString("severity"),
                toInstant(rs.getTimestamp("last_fired_at")),
                (Double) rs.getObject("last_value"),
                rs.getBoolean("last_firing"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private static Timestamp toTs(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    private static String blankToNull(String s) {
        return hasText(s) ? s.trim() : null;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    public record AlertThresholdRecord(
            String id,
            String name,
            String metricKey,
            String comparator,
            double thresholdValue,
            Integer windowSeconds,
            String instanceId,
            boolean enabled,
            String severity,
            Instant lastFiredAt,
            Double lastValue,
            boolean lastFiring,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
