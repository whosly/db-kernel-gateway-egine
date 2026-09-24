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
import java.util.UUID;

/**
 * Control-plane operation audit (never stores passwords or masking keys).
 */
@Repository
public class ConsoleAuditStore {

    private static final Logger log = LoggerFactory.getLogger(ConsoleAuditStore.class);

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS gateway_console_audit (
              id           VARCHAR(64)  PRIMARY KEY,
              at           TIMESTAMP    NOT NULL,
              action       VARCHAR(128) NOT NULL,
              instance_id  VARCHAR(128),
              detail_json  VARCHAR(4000),
              actor        VARCHAR(128)
            )
            """;

    private static final RowMapper<ConsoleAuditRecord> ROW_MAPPER = ConsoleAuditStore::mapRow;

    private final JdbcTemplate jdbc;

    public ConsoleAuditStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        jdbc.execute(DDL);
        log.info("Console control-plane table gateway_console_audit ready");
    }

    public ConsoleAuditRecord insert(String action, String instanceId, String detailJson, String actor) {
        Objects.requireNonNull(action, "action");
        Instant at = Instant.now();
        String id = UUID.randomUUID().toString().replace("-", "");
        String safeActor = actor != null && !actor.isBlank() ? actor : "console";
        jdbc.update(
                """
                INSERT INTO gateway_console_audit (id, at, action, instance_id, detail_json, actor)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                id, Timestamp.from(at), action, instanceId, truncate(detailJson, 3900), safeActor);
        return new ConsoleAuditRecord(id, at, action, instanceId, detailJson, safeActor);
    }

    public List<ConsoleAuditRecord> listRecent(int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        return jdbc.query(
                "SELECT id, at, action, instance_id, detail_json, actor FROM gateway_console_audit "
                        + "ORDER BY at DESC FETCH FIRST ? ROWS ONLY",
                ROW_MAPPER,
                capped);
    }

    private static ConsoleAuditRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp at = rs.getTimestamp("at");
        return new ConsoleAuditRecord(
                rs.getString("id"),
                at != null ? at.toInstant() : Instant.EPOCH,
                rs.getString("action"),
                rs.getString("instance_id"),
                rs.getString("detail_json"),
                rs.getString("actor"));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record ConsoleAuditRecord(
            String id,
            Instant at,
            String action,
            String instanceId,
            String detailJson,
            String actor
    ) {
    }
}
