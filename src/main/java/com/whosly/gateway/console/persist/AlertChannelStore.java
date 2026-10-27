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
 * Control-plane H2 store for alert notification channels (webhook / email).
 * Secrets inside {@code config_json} are sealed by the service layer before insert/update.
 */
@Repository
public class AlertChannelStore {

    private static final Logger log = LoggerFactory.getLogger(AlertChannelStore.class);

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS gateway_alert_channel (
              id                 VARCHAR(128)  PRIMARY KEY,
              name               VARCHAR(256)  NOT NULL,
              channel_type       VARCHAR(16)   NOT NULL,
              enabled            BOOLEAN       NOT NULL DEFAULT TRUE,
              min_severity       VARCHAR(16)   NOT NULL DEFAULT 'INFO',
              cooldown_seconds   INT           NOT NULL DEFAULT 300,
              config_json        CLOB          NOT NULL,
              last_sent_at       TIMESTAMP,
              last_ok            BOOLEAN,
              last_error         VARCHAR(1024),
              last_status_code   INT,
              created_at         TIMESTAMP     NOT NULL,
              updated_at         TIMESTAMP     NOT NULL
            )
            """;

    private static final String IDX = """
            CREATE INDEX IF NOT EXISTS idx_alert_channel_enabled
              ON gateway_alert_channel (enabled)
            """;

    private static final RowMapper<AlertChannelRecord> ROW_MAPPER = AlertChannelStore::mapRow;

    private final JdbcTemplate jdbc;

    public AlertChannelStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        jdbc.execute(DDL);
        jdbc.execute(IDX);
        log.info("Console control-plane table gateway_alert_channel ready (H2)");
    }

    public List<AlertChannelRecord> findAll() {
        return jdbc.query(
                """
                SELECT id, name, channel_type, enabled, min_severity, cooldown_seconds, config_json,
                       last_sent_at, last_ok, last_error, last_status_code, created_at, updated_at
                FROM gateway_alert_channel
                ORDER BY name ASC, id ASC
                """,
                ROW_MAPPER);
    }

    public List<AlertChannelRecord> findEnabled() {
        return jdbc.query(
                """
                SELECT id, name, channel_type, enabled, min_severity, cooldown_seconds, config_json,
                       last_sent_at, last_ok, last_error, last_status_code, created_at, updated_at
                FROM gateway_alert_channel
                WHERE enabled = TRUE
                ORDER BY name ASC, id ASC
                """,
                ROW_MAPPER);
    }

    public Optional<AlertChannelRecord> findById(String id) {
        List<AlertChannelRecord> rows = jdbc.query(
                """
                SELECT id, name, channel_type, enabled, min_severity, cooldown_seconds, config_json,
                       last_sent_at, last_ok, last_error, last_status_code, created_at, updated_at
                FROM gateway_alert_channel
                WHERE id = ?
                """,
                ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    public AlertChannelRecord insert(AlertChannelRecord row) {
        Objects.requireNonNull(row, "row");
        Instant now = Instant.now();
        String id = hasText(row.id()) ? row.id().trim() : UUID.randomUUID().toString().replace("-", "");
        Instant created = row.createdAt() != null ? row.createdAt() : now;
        Instant updated = row.updatedAt() != null ? row.updatedAt() : now;
        jdbc.update(
                """
                INSERT INTO gateway_alert_channel
                  (id, name, channel_type, enabled, min_severity, cooldown_seconds, config_json,
                   last_sent_at, last_ok, last_error, last_status_code, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                row.name(),
                row.channelType(),
                row.enabled(),
                row.minSeverity(),
                row.cooldownSeconds(),
                row.configJson(),
                toTs(row.lastSentAt()),
                row.lastOk(),
                truncate(row.lastError(), 1024),
                row.lastStatusCode(),
                Timestamp.from(created),
                Timestamp.from(updated));
        return findById(id).orElseThrow();
    }

    public AlertChannelRecord update(AlertChannelRecord row) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(row.id(), "id");
        Instant now = Instant.now();
        int n = jdbc.update(
                """
                UPDATE gateway_alert_channel
                SET name = ?, channel_type = ?, enabled = ?, min_severity = ?, cooldown_seconds = ?,
                    config_json = ?, updated_at = ?
                WHERE id = ?
                """,
                row.name(),
                row.channelType(),
                row.enabled(),
                row.minSeverity(),
                row.cooldownSeconds(),
                row.configJson(),
                Timestamp.from(now),
                row.id());
        if (n == 0) {
            throw new IllegalArgumentException("告警通道不存在: " + row.id());
        }
        return findById(row.id()).orElseThrow();
    }

    public void updateDeliveryState(String id, boolean ok, String error, Integer statusCode, Instant sentAt) {
        jdbc.update(
                """
                UPDATE gateway_alert_channel
                SET last_sent_at = ?, last_ok = ?, last_error = ?, last_status_code = ?, updated_at = ?
                WHERE id = ?
                """,
                Timestamp.from(sentAt != null ? sentAt : Instant.now()),
                ok,
                truncate(error, 1024),
                statusCode,
                Timestamp.from(Instant.now()),
                id);
    }

    public boolean delete(String id) {
        return jdbc.update("DELETE FROM gateway_alert_channel WHERE id = ?", id) > 0;
    }

    private static AlertChannelRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AlertChannelRecord(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("channel_type"),
                rs.getBoolean("enabled"),
                rs.getString("min_severity"),
                rs.getInt("cooldown_seconds"),
                rs.getString("config_json"),
                toInstant(rs.getTimestamp("last_sent_at")),
                (Boolean) rs.getObject("last_ok"),
                rs.getString("last_error"),
                (Integer) rs.getObject("last_status_code"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private static Timestamp toTs(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    public record AlertChannelRecord(
            String id,
            String name,
            String channelType,
            boolean enabled,
            String minSeverity,
            int cooldownSeconds,
            String configJson,
            Instant lastSentAt,
            Boolean lastOk,
            String lastError,
            Integer lastStatusCode,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
