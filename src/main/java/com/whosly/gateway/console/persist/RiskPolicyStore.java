package com.whosly.gateway.console.persist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Singleton control-plane risk policy override ({@code gateway_risk_policy}).
 * When no row exists, callers fall back to YAML {@code gateway.risk.*}.
 */
@Repository
public class RiskPolicyStore {

    public static final String SINGLETON_ID = "default";

    private static final Logger log = LoggerFactory.getLogger(RiskPolicyStore.class);

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS gateway_risk_policy (
              id                         VARCHAR(32)   PRIMARY KEY,
              enabled                    BOOLEAN       NOT NULL,
              denied_operations          VARCHAR(4000),
              denied_statement_keywords  VARCHAR(4000),
              updated_at                 TIMESTAMP     NOT NULL
            )
            """;

    private final JdbcTemplate jdbc;

    public RiskPolicyStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        jdbc.execute(DDL);
        log.info("Console control-plane table gateway_risk_policy ready");
    }

    public Optional<RiskPolicyRecord> find() {
        List<RiskPolicyRecord> rows = jdbc.query(
                """
                SELECT id, enabled, denied_operations, denied_statement_keywords, updated_at
                FROM gateway_risk_policy WHERE id = ?
                """,
                (rs, rowNum) -> new RiskPolicyRecord(
                        rs.getString("id"),
                        rs.getBoolean("enabled"),
                        splitCsv(rs.getString("denied_operations")),
                        splitCsv(rs.getString("denied_statement_keywords")),
                        toInstant(rs.getTimestamp("updated_at"))),
                SINGLETON_ID);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public RiskPolicyRecord upsert(boolean enabled,
                                   List<String> deniedOperations,
                                   List<String> deniedStatementKeywords) {
        Instant now = Instant.now();
        String ops = joinCsv(deniedOperations);
        String kws = joinCsv(deniedStatementKeywords);
        int updated = jdbc.update(
                """
                UPDATE gateway_risk_policy
                SET enabled = ?, denied_operations = ?, denied_statement_keywords = ?, updated_at = ?
                WHERE id = ?
                """,
                enabled, ops, kws, Timestamp.from(now), SINGLETON_ID);
        if (updated == 0) {
            jdbc.update(
                    """
                    INSERT INTO gateway_risk_policy
                      (id, enabled, denied_operations, denied_statement_keywords, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    SINGLETON_ID, enabled, ops, kws, Timestamp.from(now));
        }
        return new RiskPolicyRecord(SINGLETON_ID, enabled,
                splitCsv(ops), splitCsv(kws), now);
    }

    public void clear() {
        jdbc.update("DELETE FROM gateway_risk_policy WHERE id = ?", SINGLETON_ID);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : Instant.EPOCH;
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static String joinCsv(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(","));
    }

    public record RiskPolicyRecord(
            String id,
            boolean enabled,
            List<String> deniedOperations,
            List<String> deniedStatementKeywords,
            Instant updatedAt
    ) {
    }
}
