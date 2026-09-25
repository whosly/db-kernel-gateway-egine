package com.whosly.gateway.controller.console;

import com.whosly.gateway.adapter.AbstractProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapterRegistry;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.config.GatewayCatalogProperties;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.config.GatewayInstanceProperties;
import com.whosly.gateway.console.GatewayInstanceRegistry;
import com.whosly.gateway.console.SupportedDatabaseCatalog;
import com.whosly.gateway.console.masking.InstanceMaskingEngineFactory;
import com.whosly.gateway.console.masking.InstanceMaskingRuleCompiler;
import com.whosly.gateway.console.persist.ConsoleInstanceStore;
import com.whosly.gateway.console.persist.MaskingRuleStore;
import com.whosly.gateway.controller.console.ConsoleApiModels.MaskingRuleBody;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import com.whosly.gateway.runtime.GatewayListenerRuntime.CreateInstanceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsoleApiMaskingRulesTest {

    private ConsoleApiController console;
    private GatewayListenerRuntime runtime;
    private String instanceId;

    @BeforeEach
    void setUp() throws Exception {
        GatewayConfig gatewayConfig = new GatewayConfig();
        ReflectionTestUtils.setField(gatewayConfig, "proxyDbType", "mysql");
        ReflectionTestUtils.setField(gatewayConfig, "proxyPort", 33307);
        ReflectionTestUtils.setField(gatewayConfig, "targetHost", "127.0.0.1");
        ReflectionTestUtils.setField(gatewayConfig, "targetPort", 3306);
        ReflectionTestUtils.setField(gatewayConfig, "targetUsername", "root");
        ReflectionTestUtils.setField(gatewayConfig, "targetPassword", "s3cret-should-not-leak");
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

        ProtocolAdapterRegistry adapterRegistry = ProtocolAdapterRegistry.withBuiltIns();
        GatewayCatalogProperties catalogProps = new GatewayCatalogProperties();
        GatewayCatalogProperties.DatabaseEntry mysql = new GatewayCatalogProperties.DatabaseEntry();
        mysql.setId("mysql");
        mysql.setDisplayName("MySQL");
        mysql.setEnabled(true);
        mysql.setMaturity("ga");
        catalogProps.setDatabases(List.of(mysql));
        SupportedDatabaseCatalog catalog =
                new SupportedDatabaseCatalog(catalogProps, adapterRegistry);

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:api-masking;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ConsoleInstanceStore instanceStore = new ConsoleInstanceStore(jdbc);
        MaskingRuleStore ruleStore = new MaskingRuleStore(jdbc);
        InstanceMaskingEngineFactory factory = new InstanceMaskingEngineFactory(
                ruleStore,
                new InstanceMaskingRuleCompiler(Optional.empty(), "default"),
                List.of());

        GatewayInstanceProperties instanceProperties = new GatewayInstanceProperties();
        runtime = new GatewayListenerRuntime(
                gatewayConfig, adapterRegistry, instanceProperties, catalog,
                instanceStore, factory);

        int port = freePort();
        runtime.addInstance(new CreateInstanceRequest(
                "api-mask", "API脱敏", "mysql", "127.0.0.1", port,
                "127.0.0.1", 3306, "mysql", "root", "s3cret-should-not-leak", true));
        instanceId = "api-mask";

        GatewayInstanceRegistry registry = new GatewayInstanceRegistry(runtime, factory);
        console = new ConsoleApiController(
                catalog, registry, runtime.getLegacyAdapter(),
                new GatewayRuntimeMetrics(), gatewayConfig);
    }

    @Test
    void crudMaskingRulesAndRejectEncryptWithoutKey() {
        try {
            Map<String, Object> created = console.createMaskingRule(instanceId, new MaskingRuleBody(
                    null, "email-fixed", "fixed", 10,
                    "email", null, null, "REDACTED",
                    null, null, null, true));
            assertThat(created.get("id")).isNotNull();
            assertThat(created.get("fixedValue")).isEqualTo("REDACTED");
            assertThat(created.toString()).doesNotContain("s3cret-should-not-leak");
            String ruleId = String.valueOf(created.get("id"));

            Map<String, Object> list = console.listMaskingRules(instanceId);
            assertThat(list.get("count")).isEqualTo(1);

            AbstractProtocolAdapter adapter =
                    (AbstractProtocolAdapter) runtime.find(instanceId).orElseThrow().adapter();
            assertThat(adapter.getMaskingEngine().isActive()).isTrue();

            Map<String, Object> updated = console.updateMaskingRule(instanceId, ruleId, new MaskingRuleBody(
                    ruleId, "email-null", "null", 20,
                    "email", "users", null, null,
                    null, null, null, true));
            assertThat(updated.get("strategy")).isEqualTo("null");
            assertThat(updated.get("name")).isEqualTo("email-null");

            assertThatThrownBy(() -> console.createMaskingRule(instanceId, new MaskingRuleBody(
                    null, "secret-enc", "encrypt", 1,
                    "secret", null, null, null,
                    null, null, null, true)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("gateway.masking.key-base64");

            Map<String, Object> deleted = console.deleteMaskingRule(instanceId, ruleId);
            assertThat(deleted.get("ok")).isEqualTo(true);
            assertThat(console.listMaskingRules(instanceId).get("count")).isEqualTo(0);
            assertThat(adapter.getMaskingEngine().isActive()).isFalse();
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
