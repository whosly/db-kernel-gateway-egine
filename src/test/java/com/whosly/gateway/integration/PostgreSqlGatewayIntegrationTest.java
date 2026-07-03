package com.whosly.gateway.integration;

import com.whosly.gateway.adapter.PostgreSQLProtocolAdapter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class PostgreSqlGatewayIntegrationTest extends DatabaseGatewayIntegrationTestSupport {

    @Test
    void proxiesRealPostgreSqlQueryAndPreparedStatementThroughGateway() throws Exception {
        requireIntegrationEnabled();

        PostgreSQLProtocolAdapter adapter = new PostgreSQLProtocolAdapter();
        int proxyPort = freePort();
        adapter.setPort(proxyPort);
        adapter.setTargetHost(CONFIG.postgreSqlHost());
        adapter.setTargetPort(CONFIG.postgreSqlPort());
        adapter.setDatabaseTrafficObserver(observedEvents::add);

        try {
            adapter.start();

            String url = "jdbc:postgresql://localhost:" + proxyPort + "/" + CONFIG.postgreSqlDatabase()
                    + "?sslmode=disable";
            try (Connection connection = DriverManager.getConnection(url,
                    CONFIG.postgreSqlUsername(), CONFIG.postgreSqlPassword());
                 Statement statement = connection.createStatement()) {
                try (ResultSet resultSet = statement.executeQuery("select 1")) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(1);
                }

                try (PreparedStatement preparedStatement = connection.prepareStatement("select ?::int4")) {
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
                    .contains("PARSE", "EXECUTE");
        } finally {
            stopQuietly(adapter);
        }
    }
}
