package com.whosly.gateway.integration;

import com.whosly.gateway.adapter.MySqlProtocolAdapter;
import com.whosly.gateway.adapter.mysql.MySQLSession;
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
}
