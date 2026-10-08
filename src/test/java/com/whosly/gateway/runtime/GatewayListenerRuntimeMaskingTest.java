package com.whosly.gateway.runtime;

import com.whosly.gateway.adapter.AbstractProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapterRegistry;
import com.whosly.gateway.config.GatewayCatalogProperties;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.config.GatewayInstanceProperties;
import com.whosly.gateway.console.SupportedDatabaseCatalog;
import com.whosly.gateway.console.masking.InstanceMaskingEngineFactory;
import com.whosly.gateway.console.masking.InstanceMaskingRuleCompiler;
import com.whosly.gateway.console.persist.ConsoleInstanceStore;
import com.whosly.gateway.console.persist.MaskingRuleRecord;
import com.whosly.gateway.console.persist.MaskingRuleStore;
import com.whosly.gateway.masking.ColumnMetadata;
import com.whosly.gateway.masking.MaskedValue;
import com.whosly.gateway.masking.MaskingEngine;
import com.whosly.gateway.runtime.GatewayListenerRuntime.CreateInstanceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayListenerRuntimeMaskingTest {

    private GatewayConfig gatewayConfig;
    private ProtocolAdapterRegistry adapterRegistry;
    private SupportedDatabaseCatalog catalog;
    private GatewayInstanceProperties instanceProperties;
    private ConsoleInstanceStore instanceStore;
    private MaskingRuleStore ruleStore;
    private InstanceMaskingEngineFactory maskingFactory;

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
        GatewayCatalogProperties.DatabaseEntry mysql = new GatewayCatalogProperties.DatabaseEntry();
        mysql.setId("mysql");
        mysql.setDisplayName("MySQL");
        mysql.setEnabled(true);
        mysql.setMaturity("ga");
        catalogProps.setDatabases(List.of(mysql));
        catalog = new SupportedDatabaseCatalog(catalogProps, adapterRegistry);
        instanceProperties = new GatewayInstanceProperties();

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:rt-masking;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        instanceStore = new ConsoleInstanceStore(jdbc);
        ruleStore = new MaskingRuleStore(jdbc);
        maskingFactory = new InstanceMaskingEngineFactory(
                ruleStore,
                new InstanceMaskingRuleCompiler(Optional.empty(), "default"),
                List.of());
    }

    @Test
    void reloadMaskingHotSwapsEngineWithoutBouncingListener() throws Exception {
        int port = freePort();
        GatewayListenerRuntime runtime = new GatewayListenerRuntime(
                gatewayConfig, adapterRegistry, instanceProperties, catalog,
                instanceStore, maskingFactory);
        try {
            runtime.addInstance(new CreateInstanceRequest(
                    "rt-mask", "脱敏实例", "mysql", "127.0.0.1", port,
                    "127.0.0.1", 3306, "mysql", "root", "lab", true));

            AbstractProtocolAdapter adapter =
                    (AbstractProtocolAdapter) runtime.find("rt-mask").orElseThrow().adapter();
            assertThat(adapter.isRunning()).isTrue();
            assertThat(adapter.getMaskingEngine().isActive()).isFalse();

            ruleStore.insert(new MaskingRuleRecord(
                    null, "rt-mask", "email-fixed", "fixed", 10,
                    "email", null, null, "REDACTED", null, null, null,
                    true, null, null));

            Map<String, Object> reload = runtime.reloadMasking("rt-mask");
            assertThat(reload.get("ok")).isEqualTo(true);
            assertThat(reload.get("listenerBounced")).isEqualTo(false);
            assertThat(reload.get("maskingActive")).isEqualTo(true);
            assertThat(adapter.isRunning()).isTrue();

            MaskingEngine engine = adapter.getMaskingEngine();
            assertThat(engine.isActive()).isTrue();
            MaskedValue masked = engine.apply(
                    ColumnMetadata.text("email"),
                    MaskedValue.ofText("alice@example.com", StandardCharsets.UTF_8));
            assertThat(masked.asText(StandardCharsets.UTF_8)).isEqualTo("REDACTED");
        } finally {
            runtime.destroy();
        }
    }

    @Test
    void removeInstanceCascadesMaskingRules() throws Exception {
        int port = freePort();
        GatewayListenerRuntime runtime = new GatewayListenerRuntime(
                gatewayConfig, adapterRegistry, instanceProperties, catalog,
                instanceStore, maskingFactory);
        try {
            runtime.addInstance(new CreateInstanceRequest(
                    "rt-del", "删", "mysql", "127.0.0.1", port,
                    "127.0.0.1", 3306, "mysql", "root", "lab", true));
            ruleStore.insert(new MaskingRuleRecord(
                    null, "rt-del", "r1", "null", 1,
                    "email", null, null, null, null, null, null,
                    true, null, null));
            assertThat(ruleStore.findByInstanceId("rt-del")).hasSize(1);

            Map<String, Object> removed = runtime.removeInstance("rt-del");
            assertThat(removed.get("ok")).isEqualTo(true);
            assertThat(ruleStore.findByInstanceId("rt-del")).isEmpty();
        } finally {
            runtime.destroy();
        }
    }

    @Test
    void instanceRulesWinOverGlobalBeansViaPriorityBoost() throws Exception {
        int port = freePort();
        // Global bean would null email; instance fixed should win.
        var globalNull = new com.whosly.gateway.masking.NullingRule(
                "global-email", 100, com.whosly.gateway.masking.ColumnSelector.named("email"));
        InstanceMaskingEngineFactory factory = new InstanceMaskingEngineFactory(
                ruleStore,
                new InstanceMaskingRuleCompiler(Optional.empty(), "default"),
                List.of(globalNull));

        GatewayListenerRuntime runtime = new GatewayListenerRuntime(
                gatewayConfig, adapterRegistry, instanceProperties, catalog,
                instanceStore, factory);
        try {
            runtime.addInstance(new CreateInstanceRequest(
                    "rt-prio", "优先级", "mysql", "127.0.0.1", port,
                    "127.0.0.1", 3306, "mysql", "root", "lab", true));
            ruleStore.insert(new MaskingRuleRecord(
                    null, "rt-prio", "email-fixed", "fixed", 0,
                    "email", null, null, "FROM-INSTANCE", null, null, null,
                    true, null, null));
            MaskingEngine engine = factory.buildFor("rt-prio");
            MaskedValue out = engine.apply(
                    ColumnMetadata.text("email"),
                    MaskedValue.ofText("alice@example.com", StandardCharsets.UTF_8));
            assertThat(out.asText(StandardCharsets.UTF_8)).isEqualTo("FROM-INSTANCE");
            assertThat(out.isNull()).isFalse();
        } finally {
            runtime.destroy();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
