package com.whosly.gateway.integration;

import com.whosly.gateway.adapter.MySqlProtocolAdapter;
import com.whosly.gateway.masking.ColumnSelector;
import com.whosly.gateway.masking.FixedValueRule;
import com.whosly.gateway.masking.MaskingEngine;
import com.whosly.gateway.masking.MaskingRuleRegistry;
import com.whosly.gateway.masking.NullingRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies masking against a real MySQL server.
 *
 * <p>What only a real server can show: the column metadata the gateway matches on comes
 * from the server's own {@code ColumnDefinition} packets, and a real client driver must
 * still parse every rewritten row. Both protocol formats are covered — a plain statement
 * (text protocol) and a prepared statement (binary protocol, where values are type-encoded
 * behind a null bitmap) — and the column no rule claims is asserted to be untouched, so
 * selectivity is proven rather than assumed.</p>
 *
 * <p>Nullability is part of that metadata, and it is why these tests read a table rather
 * than a literal: a literal column is {@code NOT NULL}, and {@link NullingRule} refuses to
 * write NULL into a NOT NULL column by design. A deployment that wants to blank out such a
 * column uses a rule without that requirement, which is the constant-rule case below.</p>
 *
 * <p>These tests are skipped unless a reachable MySQL is configured in
 * {@code integration-test-local.properties}.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@Tag("integration")
class MySqlGatewayMaskingIntegrationTest extends DatabaseGatewayIntegrationTestSupport {

    private static final String PROBE_TABLE = "gateway_mask_probe";

    @Test
    void masksANullableColumnInTheTextProtocol() throws Exception {
        requireIntegrationEnabled();

        assertThat(emailOfProbeTable(engineNullingEmail(), false)).isNull();
    }

    @Test
    void masksANullableColumnInTheBinaryProtocol() throws Exception {
        requireIntegrationEnabled();

        /*
         * A prepared statement makes the server answer in the binary protocol: the same
         * masking decision has to survive a row whose values are type-encoded behind a null
         * bitmap, and a real driver has to parse the rewritten row.
         */
        assertThat(emailOfProbeTable(engineNullingEmail(), true)).isNull();
    }

    @Test
    void rewritesANotNullLiteralWithARuleThatDoesNotRequireNullable() throws Exception {
        requireIntegrationEnabled();

        /*
         * The literal is NOT NULL, so the nulling rule above deliberately does not apply.
         * A constant rule has no such requirement, which is what an operator needs for a
         * NOT NULL column — and asserting it here keeps that difference from being a
         * surprise in production.
         */
        assertThat(emailOfLiteral(engineFixedValue())).isEqualTo("***");
    }

    @Test
    void forwardsTheRealValueWhenNoRuleIsRegistered() throws Exception {
        requireIntegrationEnabled();

        // The default deployment masks nothing: the gateway must be transparent to a real
        // client even with the masking path compiled in.
        assertThat(emailOfLiteral(MaskingEngine.inactive())).isEqualTo("alice@example.com");
    }

    /**
     * Reads the {@code email} column of a row written into a temporary table, so the column
     * is nullable and the test leaves nothing behind in a shared database.
     */
    private String emailOfProbeTable(MaskingEngine engine, boolean prepared) throws Exception {
        return throughGateway(engine, connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("create temporary table " + PROBE_TABLE
                        + " (id int, email varchar(64))");
                statement.execute("insert into " + PROBE_TABLE + " values (7, 'alice@example.com')");
            }
            if (prepared) {
                try (PreparedStatement preparedStatement =
                             connection.prepareStatement("select id, email from " + PROBE_TABLE + " where id = ?")) {
                    preparedStatement.setInt(1, 7);
                    try (ResultSet resultSet = preparedStatement.executeQuery()) {
                        return readEmail(resultSet);
                    }
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("select id, email from " + PROBE_TABLE)) {
                return readEmail(resultSet);
            }
        });
    }

    /** Reads the {@code email} of a literal column, which the server reports as NOT NULL. */
    private String emailOfLiteral(MaskingEngine engine) throws Exception {
        return throughGateway(engine, connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "select 7 as id, 'alice@example.com' as email")) {
                return readEmail(resultSet);
            }
        });
    }

    private String readEmail(ResultSet resultSet) throws Exception {
        assertThat(resultSet.next()).isTrue();
        // The column no rule claims keeps its value, so masking is selective.
        assertThat(resultSet.getInt("id")).isEqualTo(7);
        String email = resultSet.getString("email");
        assertThat(resultSet.wasNull()).isEqualTo(email == null);
        return email;
    }

    /** Runs one query through a freshly started gateway and returns what the client read. */
    private String throughGateway(MaskingEngine engine, SqlQuery query) throws Exception {
        MySqlProtocolAdapter adapter = new MySqlProtocolAdapter();
        int proxyPort = freePort();
        adapter.setPort(proxyPort);
        adapter.setTargetHost(CONFIG.mysqlHost());
        adapter.setTargetPort(CONFIG.mysqlPort());
        adapter.setMaskingEngine(engine);

        try {
            adapter.start();
            String url = "jdbc:mysql://localhost:" + proxyPort + "/" + CONFIG.mysqlDatabase()
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            try (Connection connection = DriverManager.getConnection(url,
                    CONFIG.mysqlUsername(), CONFIG.mysqlPassword())) {
                return query.run(connection);
            }
        } finally {
            stopQuietly(adapter);
        }
    }

    private static MaskingEngine engineNullingEmail() {
        return new MaskingEngine(new MaskingRuleRegistry(
                List.of(new NullingRule("null-email", 10, ColumnSelector.named("email")))));
    }

    private static MaskingEngine engineFixedValue() {
        return new MaskingEngine(new MaskingRuleRegistry(
                List.of(new FixedValueRule("fixed-email", 10, ColumnSelector.named("email"), "***"))));
    }

    /** One query run against the gateway. */
    @FunctionalInterface
    private interface SqlQuery {
        String run(Connection connection) throws Exception;
    }
}
