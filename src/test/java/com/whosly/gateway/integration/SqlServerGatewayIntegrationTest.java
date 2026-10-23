package com.whosly.gateway.integration;

import com.whosly.gateway.adapter.SqlServerProtocolAdapter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional SQL Server (TDS) smoke through the transparent gateway.
 *
 * <p>Skipped unless {@code integration.enabled=true} and
 * {@code integration.sqlserver.password} is set (e.g. Compose profile
 * {@code sqlserver} on host port 11433). Default {@code mvn test} excludes
 * {@code *IntegrationTest}; this environment has no Docker/podman, so CI
 * stays green without a real MSSQL.</p>
 *
 * <p>See {@code docs/SQLSERVER_TDS_PLAN.md} §5 and {@code docker-compose.yml}
 * profile {@code sqlserver}.</p>
 */
@Tag("integration")
class SqlServerGatewayIntegrationTest extends DatabaseGatewayIntegrationTestSupport {

    @Test
    void proxiesRealSqlServerQueryThroughGateway() throws Exception {
        requireIntegrationEnabled();
        requireSqlServerLab();

        SqlServerProtocolAdapter adapter = new SqlServerProtocolAdapter();
        int proxyPort = freePort();
        adapter.setPort(proxyPort);
        adapter.setTargetHost(CONFIG.sqlServerHost());
        adapter.setTargetPort(CONFIG.sqlServerPort());
        adapter.setDatabaseTrafficObserver(observedEvents::add);

        try {
            adapter.start();

            String url = "jdbc:sqlserver://" + "localhost:" + proxyPort
                    + ";databaseName=" + CONFIG.sqlServerDatabase()
                    + ";encrypt=false;trustServerCertificate=true";
            try (Connection connection = DriverManager.getConnection(
                    url, CONFIG.sqlServerUsername(), CONFIG.sqlServerPassword());
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT 1 AS n")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(1);
            }

            assertThat(observedEvents)
                    .extracting(event -> event.getOperation())
                    .anyMatch(op -> "SQL_BATCH".equals(op) || "LOGIN7".equals(op));
        } finally {
            stopQuietly(adapter);
        }
    }

    private void requireSqlServerLab() {
        Assumptions.assumeTrue(CONFIG.sqlServerConfigured(),
                "SQL Server integration skipped: set integration.sqlserver.password "
                        + "in integration-test-local.properties and run "
                        + "`docker compose --profile sqlserver up -d` "
                        + "(host 11433). No Docker/podman in this build environment.");
    }
}
