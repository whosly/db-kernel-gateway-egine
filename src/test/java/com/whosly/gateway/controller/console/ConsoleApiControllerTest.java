package com.whosly.gateway.controller.console;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapterRegistry;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.adapter.protocol.ProtocolSession;
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

class ConsoleApiControllerTest {

    private ConsoleApiController console;
    private GatewayConfig gatewayConfig;

    @BeforeEach
    void setUp() {
        ProtocolAdapter mysqlAdapter = mock(ProtocolAdapter.class);
        when(mysqlAdapter.isRunning()).thenReturn(true);
        when(mysqlAdapter.getProtocolName()).thenReturn("MySQL");
        when(mysqlAdapter.getDefaultPort()).thenReturn(33307);
        when(mysqlAdapter.getActiveSessions()).thenReturn(List.<ProtocolSession>of());

        ProtocolAdapter pgAdapter = mock(ProtocolAdapter.class);
        when(pgAdapter.isRunning()).thenReturn(false);
        when(pgAdapter.getProtocolName()).thenReturn("PostgreSQL");
        when(pgAdapter.getDefaultPort()).thenReturn(35433);
        when(pgAdapter.getActiveSessions()).thenReturn(List.<ProtocolSession>of());

        GatewayRuntimeMetrics metrics = new GatewayRuntimeMetrics();
        metrics.recordConnectionAccepted();

        gatewayConfig = new GatewayConfig();
        ReflectionTestUtils.setField(gatewayConfig, "proxyDbType", "mysql");
        ReflectionTestUtils.setField(gatewayConfig, "proxyPort", 33307);
        ReflectionTestUtils.setField(gatewayConfig, "targetHost", "127.0.0.1");
        ReflectionTestUtils.setField(gatewayConfig, "targetPort", 3306);
        ReflectionTestUtils.setField(gatewayConfig, "targetUsername", "root");
        ReflectionTestUtils.setField(gatewayConfig, "targetPassword", "s3cret-should-not-leak");
        ReflectionTestUtils.setField(gatewayConfig, "targetDatabase", "mysql");

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
        listeners.put("gw-1", new ManagedListener(
                "gw-1", "实例一", "mysql", "0.0.0.0", 33307, true, true,
                true, "127.0.0.1", 3306, "mysql", "root",
                mysqlAdapter, metrics));
        listeners.put("gw-2", new ManagedListener(
                "gw-2", "实例二", "postgresql", "0.0.0.0", 35433, true, true,
                true, "127.0.0.1", 5432, "postgres", "pg",
                pgAdapter, new GatewayRuntimeMetrics()));

        GatewayListenerRuntime runtime = new GatewayListenerRuntime(listeners, "gw-1");
        GatewayInstanceRegistry registry = new GatewayInstanceRegistry(runtime);

        console = new ConsoleApiController(catalog, registry, mysqlAdapter, metrics, gatewayConfig);
    }

    @Test
    void supportedDatabasesHasNoBrandFocusField() {
        Map<String, Object> body = console.supportedDatabases();
        assertThat(body).containsKey("databases");
        assertThat(body).doesNotContainKey("primaryFocus");
        assertThat(body.toString()).doesNotContain("s3cret");
    }

    @Test
    void instancesAreFirstClassAndTypeAgnostic() {
        Map<String, Object> body = console.listInstances();
        assertThat(body.get("count")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<GatewayInstance> instances = (List<GatewayInstance>) body.get("instances");
        assertThat(instances).extracting(GatewayInstance::id).containsExactly("gw-1", "gw-2");
        assertThat(instances).extracting(GatewayInstance::dbType)
                .containsExactly("mysql", "postgresql");
        assertThat(instances).allMatch(GatewayInstance::bound);
    }

    @Test
    void configSummaryMasksPassword() {
        Map<String, Object> body = console.configSummary();
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) body.get("target");
        assertThat(target.get("password")).isEqualTo("********");
        assertThat(body.toString()).doesNotContain("s3cret-should-not-leak");
        assertThat(body).doesNotContainKey("primaryMvp");
    }

    @Test
    void overviewAndPerInstanceEndpoints() {
        Map<String, Object> overview = console.overview();
        assertThat(overview).containsKeys("health", "instances", "databases", "metrics", "config");

        GatewayInstance detail = console.getInstance("gw-1");
        assertThat(detail.bound()).isTrue();
        assertThat(detail.status()).isEqualTo(GatewayInstance.InstanceStatus.RUNNING);

        Map<String, Object> status = console.instanceStatus("gw-1");
        assertThat(status.get("id")).isEqualTo("gw-1");
        assertThat(status.get("dbType")).isEqualTo("mysql");

        Map<String, Object> metrics = console.instanceMetrics("gw-1");
        assertThat(metrics).containsKey("metrics");
    }
}
