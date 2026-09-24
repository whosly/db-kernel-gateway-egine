package com.whosly.gateway.runtime;

import com.whosly.gateway.adapter.AbstractProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapterRegistry;
import com.whosly.gateway.config.GatewayCatalogProperties;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.config.GatewayInstanceProperties;
import com.whosly.gateway.console.SupportedDatabaseCatalog;
import com.whosly.gateway.console.persist.ConsoleInstanceStore;
import com.whosly.gateway.runtime.GatewayListenerRuntime.CreateInstanceRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.whosly.gateway.runtime.GatewayListenerRuntime.ManagedListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Instant;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayListenerRuntimeTest {

    private GatewayConfig gatewayConfig;
    private ProtocolAdapterRegistry adapterRegistry;
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

        GatewayCatalogProperties catalogProps = new GatewayCatalogProperties();
        catalogProps.setDatabases(List.of(
                type("mysql", "MySQL", true, "ga"),
                type("postgresql", "PostgreSQL", true, "ga"),
                type("sqlserver", "SQL Server", true, "partial"),
                type("oracle", "Oracle", false, "stub")
        ));
        catalog = new SupportedDatabaseCatalog(catalogProps, adapterRegistry);
        instanceProperties = new GatewayInstanceProperties();
    }

    @Test
    void emptyInstancesSynthesizesSingleDefault() {
        GatewayListenerRuntime runtime = newRuntime();
        assertThat(runtime.list()).hasSize(1);
        ManagedListener only = runtime.list().get(0);
        assertThat(only.id()).isEqualTo("default");
        assertThat(only.dbType()).isEqualTo("mysql");
        assertThat(only.bound()).isTrue();
        assertThat(only.adapter()).isInstanceOf(AbstractProtocolAdapter.class);
        assertThat(runtime.getLegacyId()).isEqualTo("default");
        assertThat(runtime.getLegacyAdapter()).isSameAs(only.adapter());
    }

    @Test
    void createsMysqlAndPostgresqlAdaptersIndependently() throws Exception {
        int mysqlPort = freePort();
        int pgPort = freePort();
        instanceProperties.setInstances(List.of(
                entry("gw-mysql", "业务", "mysql", mysqlPort, true),
                entry("gw-pg", "分析", "postgresql", pgPort, true)
        ));

        GatewayListenerRuntime runtime = newRuntime();
        assertThat(runtime.list()).hasSize(2);

        ManagedListener mysql = runtime.find("gw-mysql").orElseThrow();
        ManagedListener pg = runtime.find("gw-pg").orElseThrow();
        assertThat(mysql.bound()).isTrue();
        assertThat(pg.bound()).isTrue();
        assertThat(mysql.adapter().getProtocolName()).isEqualTo("MySQL");
        assertThat(pg.adapter().getProtocolName()).isEqualTo("PostgreSQL");
        assertThat(mysql.listenPort()).isEqualTo(mysqlPort);
        assertThat(pg.listenPort()).isEqualTo(pgPort);
        assertThat(mysql.metrics()).isNotSameAs(pg.metrics());

        try {
            Map<String, Object> startedMysql = runtime.start("gw-mysql");
            assertThat(startedMysql.get("ok")).isEqualTo(true);
            assertThat(mysql.adapter().isRunning()).isTrue();
            assertThat(pg.adapter().isRunning()).isFalse();

            Map<String, Object> startedPg = runtime.start("gw-pg");
            assertThat(startedPg.get("ok")).isEqualTo(true);
            assertThat(pg.adapter().isRunning()).isTrue();

            runtime.stop("gw-mysql");
            assertThat(mysql.adapter().isRunning()).isFalse();
            assertThat(pg.adapter().isRunning()).isTrue();

            runtime.stop("gw-pg");
            assertThat(pg.adapter().isRunning()).isFalse();
        } finally {
            runtime.destroy();
        }
    }

    @Test
    void oracleRemainsUnsupportedWithoutAdapter() {
        instanceProperties.setInstances(List.of(
                entry("gw-ora", "Oracle", "oracle", 31521, true)
        ));
        GatewayListenerRuntime runtime = newRuntime();
        ManagedListener ora = runtime.find("gw-ora").orElseThrow();
        assertThat(ora.creatable()).isFalse();
        assertThat(ora.adapter()).isNull();
        assertThat(ora.bound()).isFalse();

        Map<String, Object> start = runtime.start("gw-ora");
        assertThat(start.get("ok")).isEqualTo(false);
        assertThat(start.get("message").toString()).contains("不可创建");
    }

    @Test
    void duplicateListenPortsFailFast() {
        instanceProperties.setInstances(List.of(
                entry("a", "A", "mysql", 33307, true),
                entry("b", "B", "postgresql", 33307, true)
        ));
        assertThatThrownBy(this::newRuntime)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate gateway.instances listenPort 33307");
    }

    @Test
    void disabledInstanceHasNoAdapter() {
        instanceProperties.setInstances(List.of(
                entry("gw-off", "关闭", "mysql", 33307, false),
                entry("gw-on", "开启", "mysql", 33308, true)
        ));
        GatewayListenerRuntime runtime = newRuntime();
        assertThat(runtime.find("gw-off").orElseThrow().adapter()).isNull();
        assertThat(runtime.find("gw-on").orElseThrow().adapter()).isNotNull();
        assertThat(runtime.getLegacyAdapter()).isSameAs(runtime.find("gw-on").orElseThrow().adapter());
    }

    @Test
    void registryListsBothBoundWhenRunning() throws Exception {
        int mysqlPort = freePort();
        int pgPort = freePort();
        instanceProperties.setInstances(List.of(
                entry("gw-mysql", "业务", "mysql", mysqlPort, true),
                entry("gw-pg", "分析", "postgresql", pgPort, true)
        ));
        GatewayListenerRuntime runtime = newRuntime();
        try {
            runtime.start("gw-mysql");
            runtime.start("gw-pg");
            var registry = new com.whosly.gateway.console.GatewayInstanceRegistry(runtime);
            assertThat(registry.listInstances()).allMatch(i -> i.bound());
            assertThat(registry.listInstances()).allMatch(
                    i -> i.status() == com.whosly.gateway.console.GatewayInstance.InstanceStatus.RUNNING);
        } finally {
            runtime.destroy();
        }
    }

    @Test
    void legacyAdapterPrefersProxyMatch() {
        instanceProperties.setInstances(List.of(
                entry("gw-pg", "分析", "postgresql", 35433, true),
                entry("gw-mysql", "业务", "mysql", 33307, true)
        ));
        // proxyDbType=mysql proxyPort=33307 → legacy = gw-mysql
        GatewayListenerRuntime runtime = newRuntime();
        assertThat(runtime.getLegacyId()).isEqualTo("gw-mysql");
        assertThat(runtime.getLegacyAdapter().getProtocolName()).isEqualTo("MySQL");
    }


    @Test
    void addInstancePersistsToH2AndCanBeRemoved() throws Exception {
        int port = freePort();
        ConsoleInstanceStore store = memStore("rt-crud");
        GatewayListenerRuntime runtime = new GatewayListenerRuntime(
                gatewayConfig, adapterRegistry, instanceProperties, catalog, store);
        try {
            CreateInstanceRequest req = new CreateInstanceRequest(
                    "rt-mysql", "联调实例", "mysql", "127.0.0.1", port,
                    "127.0.0.1", 3306, "mysql", "root", "lab-pass", true);
            var managed = runtime.addInstance(req);
            assertThat(managed.source()).isEqualTo(GatewayListenerRuntime.SOURCE_CONSOLE);
            assertThat(managed.bound()).isTrue();
            assertThat(managed.adapter().isRunning()).isTrue();
            assertThat(store.findById("rt-mysql")).isPresent();
            assertThat(store.findById("rt-mysql").orElseThrow().targetPassword()).isEqualTo("lab-pass");

            // YAML/default cannot be deleted
            var deny = runtime.removeInstance("default");
            assertThat(deny.get("ok")).isEqualTo(false);

            var removed = runtime.removeInstance("rt-mysql");
            assertThat(removed.get("ok")).isEqualTo(true);
            assertThat(store.findById("rt-mysql")).isEmpty();
            assertThat(runtime.find("rt-mysql")).isEmpty();
        } finally {
            runtime.destroy();
        }
    }

    @Test
    void bootMergesH2ConsoleInstances() throws Exception {
        int port = freePort();
        ConsoleInstanceStore store = memStore("rt-boot");
        Instant now = Instant.now();
        store.upsert(new com.whosly.gateway.console.persist.ConsoleInstanceRecord(
                "from-h2", "已持久化", "postgresql", "0.0.0.0", port, true,
                "127.0.0.1", 5432, "postgres", "pg", "pw", now, now));

        GatewayListenerRuntime runtime = new GatewayListenerRuntime(
                gatewayConfig, adapterRegistry, instanceProperties, catalog, store);
        try {
            assertThat(runtime.find("default")).isPresent();
            assertThat(runtime.find("from-h2")).isPresent();
            assertThat(runtime.find("from-h2").orElseThrow().source())
                    .isEqualTo(GatewayListenerRuntime.SOURCE_CONSOLE);
            assertThat(runtime.find("from-h2").orElseThrow().dbType()).isEqualTo("postgresql");
        } finally {
            runtime.destroy();
        }
    }

    private static ConsoleInstanceStore memStore(String name) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        return new ConsoleInstanceStore(new JdbcTemplate(ds));
    }

    private GatewayListenerRuntime newRuntime() {
        return new GatewayListenerRuntime(gatewayConfig, adapterRegistry, instanceProperties, catalog);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
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
