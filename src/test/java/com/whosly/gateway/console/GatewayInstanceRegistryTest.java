package com.whosly.gateway.console;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapterRegistry;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.adapter.protocol.ProtocolSession;
import com.whosly.gateway.config.GatewayCatalogProperties;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.config.GatewayInstanceProperties;
import com.whosly.gateway.console.GatewayInstance.InstanceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayInstanceRegistryTest {

    private GatewayConfig gatewayConfig;
    private ProtocolAdapter adapter;
    private SupportedDatabaseCatalog catalog;
    private GatewayInstanceProperties instanceProperties;

    @BeforeEach
    void setUp() {
        gatewayConfig = new GatewayConfig();
        ReflectionTestUtils.setField(gatewayConfig, "proxyDbType", "mysql");
        ReflectionTestUtils.setField(gatewayConfig, "proxyPort", 33307);
        ReflectionTestUtils.setField(gatewayConfig, "targetHost", "127.0.0.1");
        ReflectionTestUtils.setField(gatewayConfig, "targetPort", 3306);
        ReflectionTestUtils.setField(gatewayConfig, "targetUsername", "root");
        ReflectionTestUtils.setField(gatewayConfig, "targetPassword", "secret");
        ReflectionTestUtils.setField(gatewayConfig, "targetDatabase", "mysql");

        adapter = mock(ProtocolAdapter.class);
        when(adapter.isRunning()).thenReturn(true);
        when(adapter.getProtocolName()).thenReturn("MySQL");
        when(adapter.getDefaultPort()).thenReturn(33307);
        when(adapter.getActiveSessions()).thenReturn(List.<ProtocolSession>of());

        GatewayCatalogProperties catalogProps = new GatewayCatalogProperties();
        catalogProps.setDatabases(List.of(
                type("mysql", "MySQL", true, "ga"),
                type("postgresql", "PostgreSQL", true, "ga"),
                type("sqlserver", "SQL Server", true, "partial"),
                type("oracle", "Oracle", false, "stub")
        ));
        catalog = new SupportedDatabaseCatalog(catalogProps, ProtocolAdapterRegistry.withBuiltIns());
        instanceProperties = new GatewayInstanceProperties();
    }

    @Test
    void synthesizesDefaultInstanceWhenListEmpty() {
        GatewayInstanceRegistry registry = registry();
        List<GatewayInstance> instances = registry.listInstances();
        assertThat(instances).hasSize(1);
        GatewayInstance only = instances.get(0);
        assertThat(only.id()).isEqualTo("default");
        assertThat(only.dbType()).isEqualTo("mysql");
        assertThat(only.bound()).isTrue();
        assertThat(only.status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(only.startable()).isTrue();
    }

    @Test
    void listsMixedTypesWithOnlyMatchingInstanceBound() {
        instanceProperties.setInstances(List.of(
                entry("gw-mysql", "业务", "mysql", 33307, true),
                entry("gw-pg", "分析", "postgresql", 35433, true),
                entry("gw-mssql", "遗留", "sqlserver", 31433, true)
        ));
        GatewayInstanceRegistry registry = registry();
        List<GatewayInstance> instances = registry.listInstances();
        assertThat(instances).hasSize(3);

        GatewayInstance mysql = registry.findById("gw-mysql").orElseThrow();
        assertThat(mysql.bound()).isTrue();
        assertThat(mysql.status()).isEqualTo(InstanceStatus.RUNNING);

        GatewayInstance pg = registry.findById("gw-pg").orElseThrow();
        assertThat(pg.bound()).isFalse();
        assertThat(pg.status()).isEqualTo(InstanceStatus.UNBOUND);
        assertThat(pg.startable()).isFalse();

        GatewayInstance mssql = registry.findById("gw-mssql").orElseThrow();
        assertThat(mssql.dbType()).isEqualTo("sqlserver");
        assertThat(mssql.bound()).isFalse();
    }

    @Test
    void startStopOnlyOnBoundInstance() {
        instanceProperties.setInstances(List.of(
                entry("gw-mysql", "业务", "mysql", 33307, true),
                entry("gw-pg", "分析", "postgresql", 35433, true)
        ));
        // toInstance() also probes isRunning for the bound instance — keep false so start() invokes adapter.start().
        when(adapter.isRunning()).thenReturn(false);
        GatewayInstanceRegistry registry = registry();

        Map<String, Object> started = registry.start("gw-mysql");
        verify(adapter).start();
        assertThat(started.get("ok")).isEqualTo(true);

        Map<String, Object> unbound = registry.start("gw-pg");
        assertThat(unbound.get("ok")).isEqualTo(false);
        assertThat(unbound.get("message").toString()).contains("未绑定");
    }

    @Test
    void oracleInstanceIsUnsupported() {
        instanceProperties.setInstances(List.of(
                entry("gw-ora", "Oracle", "oracle", 31521, true)
        ));
        // Process type won't match oracle port/type → still UNSUPPORTED due to not creatable
        ReflectionTestUtils.setField(gatewayConfig, "proxyDbType", "oracle");
        ReflectionTestUtils.setField(gatewayConfig, "proxyPort", 31521);
        GatewayInstanceRegistry registry = registry();
        GatewayInstance ora = registry.findById("gw-ora").orElseThrow();
        assertThat(ora.status()).isEqualTo(InstanceStatus.UNSUPPORTED);
        assertThat(ora.startable()).isFalse();
    }

    private GatewayInstanceRegistry registry() {
        return new GatewayInstanceRegistry(
                instanceProperties, gatewayConfig, catalog, adapter, new GatewayRuntimeMetrics());
    }

    private static GatewayInstanceProperties.InstanceEntry entry(
            String id, String name, String dbType, int port, boolean enabled) {
        GatewayInstanceProperties.InstanceEntry e = new GatewayInstanceProperties.InstanceEntry();
        e.setId(id);
        e.setName(name);
        e.setDbType(dbType);
        e.setListenPort(port);
        e.setEnabled(enabled);
        return e;
    }

    private static GatewayCatalogProperties.DatabaseEntry type(
            String id, String name, boolean enabled, String maturity) {
        GatewayCatalogProperties.DatabaseEntry e = new GatewayCatalogProperties.DatabaseEntry();
        e.setId(id);
        e.setDisplayName(name);
        e.setEnabled(enabled);
        e.setMaturity(maturity);
        e.setDefaultProxyPort(1);
        e.setDefaultTargetPort(1);
        return e;
    }
}
