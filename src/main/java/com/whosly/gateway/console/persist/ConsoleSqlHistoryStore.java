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
 * Control-plane SQL IDE execution history (H2). Cap + prune; never stores passwords.
 */
@Repository
public class ConsoleSqlHistoryStore {

    private static final Logger log = LoggerFactory.getLogger(ConsoleSqlHistoryStore.class);
    public static final int MAX_ROWS = 200;
    public static final int SQL_MAX_CHARS = 4096;

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS console_sql_history (
              id           VARCHAR(64)   PRIMARY KEY,
              instance_id  VARCHAR(128),
              sql_text     VARCHAR(4100) NOT NULL,
              ok           BOOLEAN       NOT NULL,
              duration_ms  BIGINT,
              row_count    INT,
              created_at   TIMESTAMP     NOT NULL,
              actor        VARCHAR(128)
            )
            """;

    private static final RowMapper<SqlHistoryRecord> ROW_MAPPER = ConsoleSqlHistoryStore::mapRow;

    private final JdbcTemplate jdbc;

    public ConsoleSqlHistoryStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        jdbc.execute(DDL);
        log.info("Console control-plane table console_sql_history ready");
    }

    public SqlHistoryRecord insert(String instanceId, String sqlText, boolean ok,
                                   Long durationMs, Integer rowCount, String actor) {
        String id = UUID.randomUUID().toString().replace("-", "");
        Instant at = Instant.now();
        String safeActor = actor != null && !actor.isBlank() ? actor : "console";
        jdbc.update(
                """
                INSERT INTO console_sql_history
                  (id, instance_id, sql_text, ok, duration_ms, row_count, created_at, actor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, instanceId, truncate(sqlText, SQL_MAX_CHARS), ok, durationMs, rowCount,
                Timestamp.from(at), safeActor);
        prune();
        return new SqlHistoryRecord(id, instanceId, truncate(sqlText, SQL_MAX_CHARS), ok,
                durationMs, rowCount, at, safeActor);
    }

    public List<SqlHistoryRecord> list(String instanceId, int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_ROWS));
        if (instanceId == null || instanceId.isBlank()) {
            return jdbc.query(
                    """
                    SELECT id, instance_id, sql_text, ok, duration_ms, row_count, created_at, actor
                    FROM console_sql_history ORDER BY created_at DESC FETCH FIRST ? ROWS ONLY
                    """,
                    ROW_MAPPER, capped);
        }
        return jdbc.query(
                """
                SELECT id, instance_id, sql_text, ok, duration_ms, row_count, created_at, actor
                FROM console_sql_history WHERE instance_id = ?
                ORDER BY created_at DESC FETCH FIRST ? ROWS ONLY
                """,
                ROW_MAPPER, instanceId.trim(), capped);
    }

    public int deleteAll() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM console_sql_history", Integer.class);
        jdbc.update("DELETE FROM console_sql_history");
        return n != null ? n : 0;
    }

    public boolean deleteById(String id) {
        return jdbc.update("DELETE FROM console_sql_history WHERE id = ?", id) > 0;
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM console_sql_history", Integer.class);
        return n != null ? n : 0;
    }

    private void prune() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM console_sql_history", Integer.class);
        if (n == null || n <= MAX_ROWS) {
            return;
        }
        int excess = n - MAX_ROWS;
        jdbc.update(
                """
                DELETE FROM console_sql_history WHERE id IN (
                  SELECT id FROM console_sql_history ORDER BY created_at ASC FETCH FIRST ? ROWS ONLY
                )
                """,
                excess);
    }

    private static SqlHistoryRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp at = rs.getTimestamp("created_at");
        return new SqlHistoryRecord(
                rs.getString("id"),
                rs.getString("instance_id"),
                rs.getString("sql_text"),
                rs.getBoolean("ok"),
                (Long) rs.getObject("duration_ms"),
                (Integer) rs.getObject("row_count"),
                at != null ? at.toInstant() : Instant.EPOCH,
                rs.getString("actor"));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String s = value.trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public record SqlHistoryRecord(
            String id,
            String instanceId,
            String sqlText,
            boolean ok,
            Long durationMs,
            Integer rowCount,
            Instant createdAt,
            String actor
    ) {
    }
}
