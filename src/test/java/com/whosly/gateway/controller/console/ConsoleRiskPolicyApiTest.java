package com.whosly.gateway.controller.console;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapterRegistry;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.adapter.protocol.MutableDatabaseRiskPolicy;
import com.whosly.gateway.config.GatewayCatalogProperties;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.console.GatewayInstanceRegistry;
import com.whosly.gateway.console.SupportedDatabaseCatalog;
import com.whosly.gateway.console.persist.ConsoleAuditStore;
import com.whosly.gateway.console.persist.RiskPolicyStore;
import com.whosly.gateway.console.security.ConsoleAuditService;
import com.whosly.gateway.console.security.RiskPolicyService;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import com.whosly.gateway.runtime.GatewayListenerRuntime.ManagedListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsoleRiskPolicyApiTest {

    private ConsoleApiController console;
    private MutableDatabaseRiskPolicy mutable;

    @BeforeEach
    void setUp() {
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(adapter.isRunning()).thenReturn(false);
        when(adapter.getProtocolName()).thenReturn("MySQL");
        when(adapter.getActiveSessions()).thenReturn(List.of());

        GatewayConfig gatewayConfig = new GatewayConfig();
        ReflectionTestUtils.setField(gatewayConfig, "proxyDbType", "mysql");
        ReflectionTestUtils.setField(gatewayConfig, "proxyPort", 33307);
        ReflectionTestUtils.setField(gatewayConfig, "targetHost", "127.0.0.1");
        ReflectionTestUtils.setField(gatewayConfig, "targetPort", 3306);
        ReflectionTestUtils.setField(gatewayConfig, "targetUsername", "root");
        ReflectionTestUtils.setField(gatewayConfig, "targetPassword", "");
        ReflectionTestUtils.setField(gatewayConfig, "targetDatabase", "mysql");
        ReflectionTestUtils.setField(gatewayConfig, "riskDeniedOperations", "");
        ReflectionTestUtils.setField(gatewayConfig, "riskDeniedStatementKeywords", "");

        GatewayCatalogProperties catalogProps = new GatewayCatalogProperties();
        GatewayCatalogProperties.DatabaseEntry mysql = new GatewayCatalogProperties.DatabaseEntry();
        mysql.setId("mysql");
        mysql.setDisplayName("MySQL");
        mysql.setEnabled(true);
        mysql.setMaturity("ga");
        catalogProps.setDatabases(List.of(mysql));
        SupportedDatabaseCatalog catalog =
                new SupportedDatabaseCatalog(catalogProps, ProtocolAdapterRegistry.withBuiltIns());

        Map<String, ManagedListener> listeners = new LinkedHashMap<>();
        listeners.put("gw-1", new ManagedListener(
                "gw-1", "一", "mysql", "0.0.0.0", 33307, true, true,
                true, "127.0.0.1", 3306, "mysql", "root",
                adapter, new GatewayRuntimeMetrics(), "config"));
        GatewayListenerRuntime runtime = new GatewayListenerRuntime(listeners, "gw-1");
        GatewayInstanceRegistry registry = new GatewayInstanceRegistry(runtime);

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:risk-api-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        RiskPolicyStore store = new RiskPolicyStore(jdbc);
        ConsoleAuditService audit = new ConsoleAuditService(new ConsoleAuditStore(jdbc));
        mutable = new MutableDatabaseRiskPolicy();
        RiskPolicyService risk = new RiskPolicyService(store, mutable, gatewayConfig, audit);
        risk.init();

        console = new ConsoleApiController(
                catalog, registry, adapter, new GatewayRuntimeMetrics(), gatewayConfig,
                audit, null, null, null, null, runtime, risk, null, null, null, null);
    }

    @Test
    void getAndPutRiskPolicy() {
        Map<String, Object> got = console.getRiskPolicy();
        assertThat(got.get("source")).isEqualTo("yaml");
        assertThat(got.get("enabled")).isEqualTo(true);

        Map<String, Object> put = console.putRiskPolicy(new ConsoleApiController.RiskPolicyBody(
                true, List.of(), List.of("drop table")));
        assertThat(put.get("ok")).isEqualTo(true);
        assertThat(put.get("source")).isEqualTo("console");
        assertThat(mutable.evaluate(DatabaseTrafficEvent.builder(
                "MySQL", "s", "COM_QUERY", "DROP TABLE t").build()).isAllowed()).isFalse();

        Map<String, Object> again = console.getRiskPolicy();
        assertThat(again.get("deniedStatementKeywords")).isEqualTo(List.of("drop table"));
    }
}
