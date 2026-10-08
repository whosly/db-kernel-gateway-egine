package com.whosly.gateway.runtime;

import com.whosly.gateway.adapter.ProtocolAdapterRegistry;
import com.whosly.gateway.config.GatewayCatalogProperties;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.config.GatewayInstanceProperties;
import com.whosly.gateway.console.SupportedDatabaseCatalog;
import com.whosly.gateway.console.persist.ConsoleInstanceStore;
import com.whosly.gateway.runtime.GatewayListenerRuntime.CloneInstanceRequest;
import com.whosly.gateway.runtime.GatewayListenerRuntime.CreateInstanceRequest;
import com.whosly.gateway.runtime.GatewayListenerRuntime.UpdateInstanceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayListenerRuntimeCrudExtraTest {

    private GatewayConfig gatewayConfig;
    private ProtocolAdapterRegistry adapterRegistry;
    private GatewayInstanceProperties instanceProperties;
    private SupportedDatabaseCatalog catalog;

    @BeforeEach
    void setUp() {
        gatewayConfig = new GatewayConfig();
        ReflectionTestUtils.setField(gatewayConfig, "proxyDbType", "mysql");
        ReflectionTestUtils.setField(gatewayConfig, "proxyPort", 33307);
        ReflectionTestUtils.setField(gatewayConfig, "targetHost", "127.0.0.1");
        ReflectionTestUtils.setField(gatewayConfig, "targetPort", 3306);
        ReflectionTestUtils.setField(gatewayConfig, "targetUsername", "root");
        ReflectionTestUtils.setField(gatewayConfig, "targetPassword", "cfg-secret");
        ReflectionTestUtils.setField(gatewayConfig, "targetDatabase", "mysql");
        ReflectionTestUtils.setField(gatewayConfig, "poolResetMode", "none");
        ReflectionTestUtils.setField(gatewayConfig, "auditEnabled", false);
        ReflectionTestUtils.setField(gatewayConfig, "maxConnections", 200);
        ReflectionTestUtils.setField(gatewayConfig, "idleTimeoutSeconds", 0L);
        ReflectionTestUtils.setField(gatewayConfig, "virtualThreads", false);
        ReflectionTestUtils.setField(gatewayConfig, "rewriteMaxMessageBytes", 1048576);
        ReflectionTestUtils.setField(gatewayConfig, "rewriteMaxHoldMillis", 1000L);
        ReflectionTestUtils.setField(gatewayConfig, "riskDeniedOperations", "");
        ReflectionTestUtils.setField(gatewayConfig, "riskDeniedStatementKeywords", "");

        adapterRegistry = ProtocolAdapterRegistry.withBuiltIns();
        instanceProperties = new GatewayInstanceProperties();
        instanceProperties.setInstances(List.of());

        GatewayCatalogProperties catalogProps = new GatewayCatalogProperties();
        GatewayCatalogProperties.DatabaseEntry mysql = new GatewayCatalogProperties.DatabaseEntry();
        mysql.setId("mysql");
        mysql.setDisplayName("MySQL");
        mysql.setEnabled(true);
        mysql.setMaturity("ga");
        mysql.setDefaultProxyPort(33307);
        mysql.setDefaultTargetPort(3306);
        GatewayCatalogProperties.DatabaseEntry pg = new GatewayCatalogProperties.DatabaseEntry();
        pg.setId("postgresql");
        pg.setDisplayName("PostgreSQL");
        pg.setEnabled(true);
        pg.setMaturity("ga");
        pg.setDefaultProxyPort(35433);
        pg.setDefaultTargetPort(5432);
        catalogProps.setDatabases(List.of(mysql, pg));
        catalog = new SupportedDatabaseCatalog(catalogProps, adapterRegistry);
    }

    @Test
    void updateConsoleInstanceKeepsPasswordWhenOmittedAndRejectsConfig() throws Exception {
        int port = freePort();
        ConsoleInstanceStore store = memStore("upd");
        GatewayListenerRuntime runtime = new GatewayListenerRuntime(
                gatewayConfig, adapterRegistry, instanceProperties, catalog, store);
        try {
            runtime.addInstance(new CreateInstanceRequest(
                    "c1", "原名", "mysql", "127.0.0.1", port,
                    "127.0.0.1", 3306, "mysql", "root", "lab-pass", true));

            var updated = runtime.updateInstance("c1", new UpdateInstanceRequest(
                    "新名", null, null, null, null, "demo", null, null, null));
            assertThat(updated.name()).isEqualTo("新名");
            assertThat(updated.targetDatabase()).isEqualTo("demo");
            assertThat(updated.passwordConfigured()).isTrue();
            assertThat(store.findById("c1").orElseThrow().targetPassword()).isEqualTo("lab-pass");

            assertThatThrownBy(() -> runtime.updateInstance("default",
                    new UpdateInstanceRequest("x", null, null, null, null, null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不可编辑");
        } finally {
            runtime.destroy();
        }
    }

    @Test
    void updateRejectsPortConflict() throws Exception {
        int p1 = freePort();
        int p2 = freePort();
        ConsoleInstanceStore store = memStore("port");
        GatewayListenerRuntime runtime = new GatewayListenerRuntime(
                gatewayConfig, adapterRegistry, instanceProperties, catalog, store);
        try {
            runtime.addInstance(new CreateInstanceRequest(
                    "a", "A", "mysql", "127.0.0.1", p1,
                    "127.0.0.1", 3306, "mysql", "root", "p", true));
            runtime.addInstance(new CreateInstanceRequest(
                    "b", "B", "mysql", "127.0.0.1", p2,
                    "127.0.0.1", 3306, "mysql", "root", "p", true));
            assertThatThrownBy(() -> runtime.updateInstance("b",
                    new UpdateInstanceRequest(null, null, p1, null, null, null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("listenPort");
        } finally {
            runtime.destroy();
        }
    }

    @Test
    void cloneCreatesNewConsoleInstanceWithPasswordCopied() throws Exception {
        int port = freePort();
        ConsoleInstanceStore store = memStore("clone");
        GatewayListenerRuntime runtime = new GatewayListenerRuntime(
                gatewayConfig, adapterRegistry, instanceProperties, catalog, store);
        try {
            runtime.addInstance(new CreateInstanceRequest(
                    "src", "源", "mysql", "127.0.0.1", port,
                    "127.0.0.1", 3306, "mysql", "root", "keep-me", true));
            var cloned = runtime.cloneInstance("src",
                    new CloneInstanceRequest("src-copy", "克隆体", null, false));
            assertThat(cloned.id()).isEqualTo("src-copy");
            assertThat(cloned.source()).isEqualTo(GatewayListenerRuntime.SOURCE_CONSOLE);
            assertThat(cloned.passwordConfigured()).isTrue();
            assertThat(cloned.listenPort()).isNotEqualTo(port);
            assertThat(store.findById("src-copy").orElseThrow().targetPassword()).isEqualTo("keep-me");
            // config source clone
            var fromCfg = runtime.cloneInstance("default",
                    new CloneInstanceRequest(null, "从配置克隆", null, false));
            assertThat(fromCfg.source()).isEqualTo(GatewayListenerRuntime.SOURCE_CONSOLE);
            assertThat(fromCfg.passwordConfigured()).isTrue();
        } finally {
            runtime.destroy();
        }
    }

    @Test
    void importCreatesWithoutPasswordAndCanSkipExisting() throws Exception {
        int port = freePort();
        ConsoleInstanceStore store = memStore("imp");
        GatewayListenerRuntime runtime = new GatewayListenerRuntime(
                gatewayConfig, adapterRegistry, instanceProperties, catalog, store);
        try {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "imp-1");
            row.put("name", "导入一");
            row.put("dbType", "mysql");
            row.put("listenHost", "0.0.0.0");
            row.put("listenPort", port);
            row.put("targetHost", "127.0.0.1");
            row.put("targetPort", 3306);
            row.put("targetDatabase", "mysql");
            row.put("targetUsername", "root");
            row.put("targetPassword", "SHOULD-IGNORE");
            row.put("enabled", true);

            Map<String, Object> first = runtime.importInstances(List.of(row), false, true);
            assertThat(first.get("created")).isEqualTo(1);
            assertThat(store.findById("imp-1").orElseThrow().targetPassword()).isNullOrEmpty();
            assertThat(runtime.find("imp-1").orElseThrow().passwordConfigured()).isFalse();

            Map<String, Object> second = runtime.importInstances(List.of(row), false, true);
            assertThat(second.get("skipped")).isEqualTo(1);
            assertThat(second.get("created")).isEqualTo(0);
        } finally {
            runtime.destroy();
        }
    }

    private static ConsoleInstanceStore memStore(String name) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:crud-" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        return new ConsoleInstanceStore(new JdbcTemplate(ds));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
