package com.whosly.gateway.integration;

import com.whosly.gateway.adapter.MySqlProtocolAdapter;
import com.whosly.gateway.adapter.mysql.MySQLSession;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class MySqlGatewayIntegrationTest extends DatabaseGatewayIntegrationTestSupport {

    @Test
    void proxiesRealMySqlQueryAndPreparedStatementThroughGateway() throws Exception {
        requireIntegrationEnabled();

        MySqlProtocolAdapter adapter = new MySqlProtocolAdapter();
        int proxyPort = freePort();
        adapter.setPort(proxyPort);
        adapter.setTargetHost(CONFIG.mysqlHost());
        adapter.setTargetPort(CONFIG.mysqlPort());
        adapter.setDatabaseTrafficObserver(observedEvents::add);

        try {
            adapter.start();

            String url = "jdbc:mysql://localhost:" + proxyPort + "/" + CONFIG.mysqlDatabase()
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            try (Connection connection = DriverManager.getConnection(url,
                    CONFIG.mysqlUsername(), CONFIG.mysqlPassword());
                 Statement statement = connection.createStatement()) {
                try (ResultSet resultSet = statement.executeQuery("select 1")) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(1);
                }

                try (PreparedStatement preparedStatement = connection.prepareStatement("select ?")) {
                    preparedStatement.setInt(1, 7);
                    try (ResultSet resultSet = preparedStatement.executeQuery()) {
                        assertThat(resultSet.next()).isTrue();
                        assertThat(resultSet.getInt(1)).isEqualTo(7);
                    }
                }
            }

            assertObservedSql("select 1");
            assertThat(observedEvents)
                    .extracting(event -> event.getOperation())
                    .contains("COM_QUERY");
        } finally {
            stopQuietly(adapter);
        }
    }

    @Test
    void proxiesTransactionsAndTargetErrorsThroughGateway() throws Exception {
        requireIntegrationEnabled();

        MySqlProtocolAdapter adapter = new MySqlProtocolAdapter();
        int proxyPort = freePort();
        adapter.setPort(proxyPort);
        adapter.setTargetHost(CONFIG.mysqlHost());
        adapter.setTargetPort(CONFIG.mysqlPort());
        adapter.setDatabaseTrafficObserver(observedEvents::add);

        try {
            adapter.start();

            String url = "jdbc:mysql://localhost:" + proxyPort + "/" + CONFIG.mysqlDatabase()
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            try (Connection connection = DriverManager.getConnection(url,
                    CONFIG.mysqlUsername(), CONFIG.mysqlPassword());
                 Statement statement = connection.createStatement()) {
                MySQLSession session = (MySQLSession) awaitActiveSession(adapter);

                try (ResultSet resultSet = statement.executeQuery("select database()")) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getString(1)).isEqualToIgnoringCase(CONFIG.mysqlDatabase());
                }
                assertThat(session.getCurrentDatabase()).hasValueSatisfying(
                        database -> assertThat(database).isEqualToIgnoringCase(CONFIG.mysqlDatabase()));

                statement.execute("begin");
                assertThat(session.getTransactionStatus())
                        .isEqualTo(MySQLSession.TransactionStatus.IN_TRANSACTION);

                statement.execute("commit");
                assertThat(session.getTransactionStatus()).isEqualTo(MySQLSession.TransactionStatus.IDLE);

                assertThatThrownBy(() -> statement.executeQuery("select * from gateway_missing_table"))
                        .isInstanceOf(SQLException.class)
                        .satisfies(error -> assertThat(((SQLException) error).getSQLState())
                                .isEqualTo("42S02"));
            }

            assertObservedSql("select database()");
        } finally {
            stopQuietly(adapter);
        }
    }

    @Test
    void proxiesLargeResultSetsAndMultiStatementsThroughGateway() throws Exception {
        requireIntegrationEnabled();

        MySqlProtocolAdapter adapter = new MySqlProtocolAdapter();
        int proxyPort = freePort();
        adapter.setPort(proxyPort);
        adapter.setTargetHost(CONFIG.mysqlHost());
        adapter.setTargetPort(CONFIG.mysqlPort());
        adapter.setDatabaseTrafficObserver(observedEvents::add);

        try {
            adapter.start();

            String largeSelect = "with recursive seq(n) as"
                    + " (select 1 union all select n + 1 from seq where n < 500)"
                    + " select n from seq";
            String url = "jdbc:mysql://localhost:" + proxyPort + "/" + CONFIG.mysqlDatabase()
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&allowMultiQueries=true";
            try (Connection connection = DriverManager.getConnection(url,
                    CONFIG.mysqlUsername(), CONFIG.mysqlPassword());
                 Statement statement = connection.createStatement()) {

                int rows = 0;
                int last = 0;
                try (ResultSet resultSet = statement.executeQuery(largeSelect)) {
                    while (resultSet.next()) {
                        rows++;
                        last = resultSet.getInt(1);
                    }
                }
                assertThat(rows).isEqualTo(500);
                assertThat(last).isEqualTo(500);

                assertThat(statement.execute("select 1; select 2")).isTrue();
                assertThat(readSingleInt(statement.getResultSet())).isEqualTo(1);
                assertThat(statement.getMoreResults()).isTrue();
                assertThat(readSingleInt(statement.getResultSet())).isEqualTo(2);
            }

            assertObservedSql(largeSelect);
        } finally {
            stopQuietly(adapter);
        }
    }

    @Test
    void proxiesPayloadLargerThanSinglePacketThroughGateway() throws Exception {
        requireIntegrationEnabled();

        MySqlProtocolAdapter adapter = new MySqlProtocolAdapter();
        int proxyPort = freePort();
        adapter.setPort(proxyPort);
        adapter.setTargetHost(CONFIG.mysqlHost());
        adapter.setTargetPort(CONFIG.mysqlPort());
        adapter.setDatabaseTrafficObserver(observedEvents::add);

        int literalLength = 16_800_000;
        String largeStatement = "select length('" + "a".repeat(literalLength) + "')";

        try {
            adapter.start();

            String url = "jdbc:mysql://localhost:" + proxyPort + "/" + CONFIG.mysqlDatabase()
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    + "&maxAllowedPacket=67108864";
            try (Connection connection = DriverManager.getConnection(url,
                    CONFIG.mysqlUsername(), CONFIG.mysqlPassword());
                 Statement statement = connection.createStatement()) {

                int maxAllowedPacket;
                try (ResultSet resultSet = statement.executeQuery("select @@max_allowed_packet")) {
                    assertThat(resultSet.next()).isTrue();
                    maxAllowedPacket = resultSet.getInt(1);
                }
                Assumptions.assumeTrue(maxAllowedPacket > largeStatement.length(),
                        "target max_allowed_packet " + maxAllowedPacket
                                + " is smaller than the test statement " + largeStatement.length());

                try (ResultSet resultSet = statement.executeQuery(largeStatement)) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(literalLength);
                }
            }

            // Reassembly must report the complete statement, not just the first 2^24 - 1 bytes.
            assertObservedStatement("select length('", largeStatement.length());
        } finally {
            stopQuietly(adapter);
        }
    }

    private static int readSingleInt(ResultSet resultSet) throws SQLException {
        assertThat(resultSet).isNotNull();
        assertThat(resultSet.next()).isTrue();
        return resultSet.getInt(1);
    }
}
