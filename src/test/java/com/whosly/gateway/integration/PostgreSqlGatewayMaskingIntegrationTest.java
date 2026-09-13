package com.whosly.gateway.integration;

import com.whosly.gateway.adapter.PostgreSQLProtocolAdapter;
import com.whosly.gateway.masking.ColumnSelector;
import com.whosly.gateway.masking.FixedValueRule;
import com.whosly.gateway.masking.MaskingEngine;
import com.whosly.gateway.masking.MaskingRuleRegistry;
import com.whosly.gateway.masking.NullingRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies masking against a real PostgreSQL server.
 *
 * <p>The column metadata comes from the server's own {@code RowDescription}, so a real
 * server is what proves the type names, categories and formats the engine matches on are
 * read correctly — and that a real driver parses every rewritten {@code DataRow}.</p>
 *
 * <p>The format of each column is chosen by the client, not by the gateway, so what the
 * driver asks for decides whether a value arrives in text or binary form. The assertions
 * are deliberately written to hold in either case: a NULL mask is valid for every type and
 * in both formats, which is exactly the property being relied on.</p>
 *
 * <p>These tests are skipped unless a reachable PostgreSQL is configured in
 * {@code integration-test-local.properties}.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@Tag("integration")
class PostgreSqlGatewayMaskingIntegrationTest extends DatabaseGatewayIntegrationTestSupport {

    @Test
    void masksAMatchedColumn() throws Exception {
        requireIntegrationEnabled();

        assertThat(columnSeenByClient(engineNullingEmail(), "email")).isNull();
    }

    @Test
    void rewritesAMatchedColumnWithTheRuleValue() throws Exception {
        requireIntegrationEnabled();

        // A constant mask exercises the other direction: the replacement has to be
        // encodable for a text column in whichever format the driver requested.
        MaskingEngine engine = new MaskingEngine(new MaskingRuleRegistry(
                List.of(new FixedValueRule("fixed-email", 10, ColumnSelector.named("email"), "***"))));

        assertThat(columnSeenByClient(engine, "email")).isEqualTo("***");
    }

    @Test
    void forwardsTheRealValueWhenNoRuleIsRegistered() throws Exception {
        requireIntegrationEnabled();

        assertThat(columnSeenByClient(MaskingEngine.inactive(), "email")).isEqualTo("alice@example.com");
    }

    /** Runs one query through the gateway and returns the value the client reads. */
    private String columnSeenByClient(MaskingEngine engine, String column) throws Exception {
        PostgreSQLProtocolAdapter adapter = new PostgreSQLProtocolAdapter();
        int proxyPort = freePort();
        adapter.setPort(proxyPort);
        adapter.setTargetHost(CONFIG.postgreSqlHost());
        adapter.setTargetPort(CONFIG.postgreSqlPort());
        adapter.setMaskingEngine(engine);

        try {
            adapter.start();
            String url = "jdbc:postgresql://localhost:" + proxyPort + "/" + CONFIG.postgreSqlDatabase()
                    + "?sslmode=disable";
            try (Connection connection = DriverManager.getConnection(url,
                    CONFIG.postgreSqlUsername(), CONFIG.postgreSqlPassword());
                 Statement statement = connection.createStatement();
                 // The cast pins the column's type, so the metadata the gateway matches on
                 // does not depend on how the server would infer an untyped literal.
                 ResultSet resultSet = statement.executeQuery(
                         "select 7 as id, 'alice@example.com'::text as email")) {
                assertThat(resultSet.next()).isTrue();
                // The column no rule claims keeps its value, so masking is selective.
                assertThat(resultSet.getInt("id")).isEqualTo(7);
                String value = resultSet.getString(column);
                assertThat(resultSet.wasNull()).isEqualTo(value == null);
                return value;
            }
        } finally {
            stopQuietly(adapter);
        }
    }

    private static MaskingEngine engineNullingEmail() {
        return new MaskingEngine(new MaskingRuleRegistry(
                List.of(new NullingRule("null-email", 10, ColumnSelector.named("email")))));
    }
}
