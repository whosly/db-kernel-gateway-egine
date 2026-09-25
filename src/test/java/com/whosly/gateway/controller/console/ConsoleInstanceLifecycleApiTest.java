package com.whosly.gateway.controller.console;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapterRegistry;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.config.GatewayCatalogProperties;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.console.GatewayInstance;
import com.whosly.gateway.console.GatewayInstanceRegistry;
import com.whosly.gateway.console.SupportedDatabaseCatalog;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import com.whosly.gateway.runtime.GatewayListenerRuntime.ManagedListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsoleInstanceLifecycleApiTest {

    private ConsoleApiController console;
    private ProtocolAdapter runningAdapter;
    private ProtocolAdapter stoppedAdapter;

    @BeforeEach
    void setUp() {
        runningAdapter = mock(ProtocolAdapter.class);
        when(runningAdapter.isRunning()).thenReturn(true);
        when(runningAdapter.getProtocolName()).thenReturn("MySQL");
        when(runningAdapter.getDefaultPort()).thenReturn(33307);
        when(runningAdapter.getActiveSessions()).thenReturn(List.of());

        stoppedAdapter = mock(ProtocolAdapter.class);
        when(stoppedAdapter.isRunning()).thenReturn(false);
        when(stoppedAdapter.getProtocolName()).thenReturn("PostgreSQL");
        when(stoppedAdapter.getDefaultPort()).thenReturn(35433);
        when(stoppedAdapter.getActiveSessions()).thenReturn(List.of());

        GatewayConfig gatewayConfig = new GatewayConfig();
        ReflectionTestUtils.setField(gatewayConfig, "proxyDbType", "mysql");
        ReflectionTestUtils.setField(gatewayConfig, "proxyPort", 33307);
        ReflectionTestUtils.setField(gatewayConfig, "targetHost", "127.0.0.1");
        ReflectionTestUtils.setField(gatewayConfig, "targetPort", 3306);
        ReflectionTestUtils.setField(gatewayConfig, "targetUsername", "root");
        ReflectionTestUtils.setField(gatewayConfig, "targetPassword", "s3cret-should-not-leak");
        ReflectionTestUtils.setField(gatewayConfig, "targetDatabase", "mysql");
        ReflectionTestUtils.setField(gatewayConfig, "auditMaskStatements", true);

        GatewayCatalogProperties catalogProps = new GatewayCatalogProperties();
        GatewayCatalogProperties.DatabaseEntry mysql = new GatewayCatalogProperties.DatabaseEntry();
        mysql.setId("mysql");
        mysql.setDisplayName("MySQL");
        mysql.setEnabled(true);
        mysql.setMaturity("ga");
        mysql.setDefaultProxyPort(33307);
        mysql.setDefaultTargetPort(3306);
        catalogProps.setDatabases(List.of(mysql));
        SupportedDatabaseCatalog catalog =
                new SupportedDatabaseCatalog(catalogProps, ProtocolAdapterRegistry.withBuiltIns());

        Map<String, ManagedListener> listeners = new LinkedHashMap<>();
        listeners.put("gw-run", new ManagedListener(
                "gw-run", "运行中", "mysql", "0.0.0.0", 33307, true, true,
                true, "127.0.0.1", 3306, "mysql", "root",
                runningAdapter, new GatewayRuntimeMetrics(), "config"));
        listeners.put("gw-stop", new ManagedListener(
                "gw-stop", "已停止", "postgresql", "0.0.0.0", 35433, true, true,
                true, "10.0.0.2", 5432, "postgres", "pg",
                stoppedAdapter, new GatewayRuntimeMetrics(), "console"));

        GatewayListenerRuntime runtime = new GatewayListenerRuntime(listeners, "gw-run");
        GatewayInstanceRegistry registry = new GatewayInstanceRegistry(runtime);
        console = new ConsoleApiController(catalog, registry, runningAdapter,
                new GatewayRuntimeMetrics(), gatewayConfig);
    }

    @Test
    void listFiltersByStatusDbTypeAndQ() {
        Map<String, Object> byStatus = console.listInstances("RUNNING", null, null);
        assertThat(byStatus.get("total")).isEqualTo(1);
        Map<String, Object> byType = console.listInstances(null, "postgresql", null);
        assertThat(byType.get("total")).isEqualTo(1);
        Map<String, Object> byQ = console.listInstances(null, null, "10.0.0");
        assertThat(byQ.get("total")).isEqualTo(1);
        Map<String, Object> byName = console.listInstances(null, null, "运行");
        assertThat(byName.get("total")).isEqualTo(1);
    }

    @Test
    void bulkStopReturnsPerIdResults() {
        Map<String, Object> body = console.bulkInstances(
                new ConsoleApiModels.BulkInstancesBody("stop", List.of("gw-run", "missing")));
        assertThat(body.get("okCount")).isEqualTo(1);
        assertThat(body.get("failCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("results");
        assertThat(results).hasSize(2);
    }

    @Test
    void exportStillOmitsSecrets() {
        List<Map<String, Object>> exported = console.exportInstances();
        String asText = exported.toString();
        assertThat(asText).doesNotContain("s3cret");
        assertThat(asText).doesNotContain("targetPassword");
        for (Map<String, Object> row : exported) {
            assertThat(row).containsKey("passwordConfigured");
            assertThat(row).doesNotContainKey("targetPassword");
            assertThat(row).doesNotContainKey("password");
        }
    }
}
