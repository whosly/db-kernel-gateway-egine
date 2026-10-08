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
 * Control-plane saved SQL snippets for the console IDE.
 */
@Repository
public class ConsoleSqlSnippetStore {

    private static final Logger log = LoggerFactory.getLogger(ConsoleSqlSnippetStore.class);
    public static final int SQL_MAX_CHARS = 32_000;
    public static final int NAME_MAX = 200;

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS console_sql_snippet (
              id          VARCHAR(64)    PRIMARY KEY,
              name        VARCHAR(200)   NOT NULL,
              sql_text    CLOB           NOT NULL,
              created_at  TIMESTAMP      NOT NULL,
              updated_at  TIMESTAMP      NOT NULL
            )
            """;

    private static final RowMapper<SqlSnippetRecord> ROW_MAPPER = ConsoleSqlSnippetStore::mapRow;

    private final JdbcTemplate jdbc;

    public ConsoleSqlSnippetStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        jdbc.execute(DDL);
        log.info("Console control-plane table console_sql_snippet ready");
    }

    public List<SqlSnippetRecord> listAll() {
        return jdbc.query(
                """
                SELECT id, name, sql_text, created_at, updated_at
                FROM console_sql_snippet ORDER BY updated_at DESC
                """,
                ROW_MAPPER);
    }

    public Optional<SqlSnippetRecord> findById(String id) {
        List<SqlSnippetRecord> rows = jdbc.query(
                """
                SELECT id, name, sql_text, created_at, updated_at
                FROM console_sql_snippet WHERE id = ?
                """,
                ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public SqlSnippetRecord insert(String name, String sqlText) {
        String id = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        String safeName = truncate(requireName(name), NAME_MAX);
        String safeSql = truncate(requireSql(sqlText), SQL_MAX_CHARS);
        jdbc.update(
                """
                INSERT INTO console_sql_snippet (id, name, sql_text, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                id, safeName, safeSql, Timestamp.from(now), Timestamp.from(now));
        return new SqlSnippetRecord(id, safeName, safeSql, now, now);
    }

    public SqlSnippetRecord update(String id, String name, String sqlText) {
        Instant now = Instant.now();
        String safeName = truncate(requireName(name), NAME_MAX);
        String safeSql = truncate(requireSql(sqlText), SQL_MAX_CHARS);
        int n = jdbc.update(
                """
                UPDATE console_sql_snippet SET name = ?, sql_text = ?, updated_at = ? WHERE id = ?
                """,
                safeName, safeSql, Timestamp.from(now), id);
        if (n == 0) {
            throw new IllegalArgumentException("Unknown snippet id: " + id);
        }
        return findById(id).orElseThrow();
    }

    public boolean delete(String id) {
        return jdbc.update("DELETE FROM console_sql_snippet WHERE id = ?", id) > 0;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("snippet name is required");
        }
        return name.trim();
    }

    private static String requireSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("snippet sql is required");
        }
        return sql;
    }

    private static SqlSnippetRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp c = rs.getTimestamp("created_at");
        Timestamp u = rs.getTimestamp("updated_at");
        return new SqlSnippetRecord(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("sql_text"),
                c != null ? c.toInstant() : Instant.EPOCH,
                u != null ? u.toInstant() : Instant.EPOCH);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record SqlSnippetRecord(
            String id,
            String name,
            String sqlText,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
