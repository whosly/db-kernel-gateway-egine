package com.whosly.gateway.console.sql;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.DenyListDatabaseRiskPolicy;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.adapter.protocol.MutableDatabaseRiskPolicy;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import com.whosly.gateway.runtime.GatewayListenerRuntime.ManagedListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstanceSqlExecuteServiceTest {

    private InstanceSqlExecuteService service;
    private MutableDatabaseRiskPolicy mutable;
    private GatewayConfig gatewayConfig;

    @BeforeEach
    void setUp() throws Exception {
        gatewayConfig = new GatewayConfig();
        ReflectionTestUtils.setField(gatewayConfig, "targetHost", "127.0.0.1");
        ReflectionTestUtils.setField(gatewayConfig, "targetPort", 0);
        ReflectionTestUtils.setField(gatewayConfig, "targetUsername", "sa");
        ReflectionTestUtils.setField(gatewayConfig, "targetPassword", "");
        ReflectionTestUtils.setField(gatewayConfig, "targetDatabase", "");

        String dbName = "sqlws_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE demo(id INT, name VARCHAR(64))");
            st.execute("INSERT INTO demo VALUES (1, 'alice'), (2, 'bob'), (3, 'carol')");
        }
        ReflectionTestUtils.setField(gatewayConfig, "targetDatabase", url);

        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(adapter.isRunning()).thenReturn(false);

        Map<String, ManagedListener> listeners = new LinkedHashMap<>();
        listeners.put("sql-h2", new ManagedListener(
                "sql-h2", "SQL lab", "h2", "0.0.0.0", 18080, true, true,
                false, "127.0.0.1", 0, url, "sa",
                adapter, new GatewayRuntimeMetrics(), "config"));

        // Wire-type instance stopped — execute must require RUNNING (proxy path).
        ProtocolAdapter mysqlAdapter = mock(ProtocolAdapter.class);
        when(mysqlAdapter.isRunning()).thenReturn(false);
        listeners.put("sql-mysql-stopped", new ManagedListener(
                "sql-mysql-stopped", "MySQL stopped", "mysql", "0.0.0.0", 33307, true, true,
                true, "127.0.0.1", 3306, "appdb", "root",
                mysqlAdapter, new GatewayRuntimeMetrics(), "config"));

        GatewayListenerRuntime runtime = new GatewayListenerRuntime(listeners, "sql-h2");

        mutable = new MutableDatabaseRiskPolicy();
        mutable.replace(DenyListDatabaseRiskPolicy.of(List.of(), List.of("drop")));
        service = new InstanceSqlExecuteService(runtime, Optional.empty(), gatewayConfig, Optional.of(mutable));
    }

    @Test
    void denyByRiskPolicy() {
        assertThatThrownBy(() -> service.execute("sql-h2", "DROP TABLE demo", 50, 5000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("风控拒绝");
    }

    @Test
    void maxRowsTruncates() {
        Map<String, Object> body = service.execute("sql-h2", "SELECT * FROM demo ORDER BY id", 2, 5000);
        assertThat(body.get("ok")).isEqualTo(true);
        assertThat(body.get("rowCount")).isEqualTo(2);
        assertThat(body.get("truncated")).isEqualTo(true);
        assertThat(body.get("viaProxy")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<String> cols = (List<String>) body.get("columns");
        assertThat(cols).isNotEmpty();
    }

    @Test
    void unknownInstanceThrows() {
        assertThatThrownBy(() -> service.execute("missing", "SELECT 1", 10, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown gateway instance");
    }

    @Test
    void rejectsMultiStatement() {
        assertThatThrownBy(() -> InstanceSqlExecuteService.validateSingleStatement("SELECT 1; SELECT 2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("单条");
    }

    @Test
    void allowsTrailingSemicolon() {
        InstanceSqlExecuteService.validateSingleStatement("SELECT 1;");
    }

    @Test
    void truncateForAudit() {
        String longSql = "SELECT " + "x".repeat(300);
        String t = InstanceSqlExecuteService.truncateForAudit(longSql, 50);
        assertThat(t.length()).isLessThanOrEqualTo(51);
        assertThat(t).endsWith("…");
    }

    @Test
    void mysqlStoppedRequiresStart() {
        // Password comes from gatewayConfig for config-source instances when empty on listener;
        // set a non-empty password so we reach the RUNNING gate.
        ReflectionTestUtils.setField(gatewayConfig, "targetPassword", "secret");
        assertThatThrownBy(() -> service.execute("sql-mysql-stopped", "SELECT 1", 10, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未启动");
    }

    @Test
    void resolveProxyConnectHostMapsWildcardToLoopback() {
        assertThat(InstanceSqlExecuteService.resolveProxyConnectHost("0.0.0.0")).isEqualTo("127.0.0.1");
        assertThat(InstanceSqlExecuteService.resolveProxyConnectHost("::")).isEqualTo("127.0.0.1");
        assertThat(InstanceSqlExecuteService.resolveProxyConnectHost("*")).isEqualTo("127.0.0.1");
        assertThat(InstanceSqlExecuteService.resolveProxyConnectHost("")).isEqualTo("127.0.0.1");
        assertThat(InstanceSqlExecuteService.resolveProxyConnectHost("192.168.1.10")).isEqualTo("192.168.1.10");
        assertThat(InstanceSqlExecuteService.resolveProxyConnectHost("127.0.0.1")).isEqualTo("127.0.0.1");
    }

    @Test
    void buildProxyJdbcUrlUsesListenPortNotTargetPort() {
        String url = InstanceSqlExecuteService.buildProxyJdbcUrl(
                "mysql", "0.0.0.0", 33307, "appdb");
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:33307/appdb");
        assertThat(url).doesNotContain(":3306/");

        String pg = InstanceSqlExecuteService.buildProxyJdbcUrl(
                "postgresql", "10.0.0.5", 35433, "testdb");
        assertThat(pg).isEqualTo("jdbc:postgresql://10.0.0.5:35433/testdb");

        String mssql = InstanceSqlExecuteService.buildProxyJdbcUrl(
                "sqlserver", "0.0.0.0", 31433, "master");
        assertThat(mssql).contains("jdbc:sqlserver://127.0.0.1:31433");
        assertThat(mssql).contains("databaseName=master");
        assertThat(mssql).doesNotContain(":1433");
    }

    @Test
    void usesWireProxyForMysqlNotH2() {
        assertThat(InstanceSqlExecuteService.usesWireProxy("mysql")).isTrue();
        assertThat(InstanceSqlExecuteService.usesWireProxy("postgresql")).isTrue();
        assertThat(InstanceSqlExecuteService.usesWireProxy("sqlserver")).isTrue();
        assertThat(InstanceSqlExecuteService.usesWireProxy("mssql")).isTrue();
        assertThat(InstanceSqlExecuteService.usesWireProxy("h2")).isFalse();
    }
}
