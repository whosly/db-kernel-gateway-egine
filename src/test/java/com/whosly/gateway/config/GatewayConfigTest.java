package com.whosly.gateway.config;

import com.whosly.gateway.adapter.AbstractProtocolAdapter;
import com.whosly.gateway.adapter.protocol.DatabaseRiskPolicy;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.RiskDecision;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficObserver;
import com.whosly.gateway.audit.AuditRecord;
import com.whosly.gateway.audit.AuditRecordCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsUnsupportedProtocolInsteadOfFallingBackToMysql() {
        GatewayConfig config = new GatewayConfig();
        ReflectionTestUtils.setField(config, "proxyDbType", "db2");

        assertThatThrownBy(config::protocolAdapter)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported gateway.proxy-db-type");
    }

    @Test
    void rejectsUnsupportedAuditDurabilityInsteadOfGuessing() {
        GatewayConfig config = new GatewayConfig();
        ReflectionTestUtils.setField(config, "auditEnabled", true);
        ReflectionTestUtils.setField(config, "auditSpoolDir", tempDir.resolve("audit").toString());
        ReflectionTestUtils.setField(config, "auditDurability", "eventual");

        assertThatThrownBy(config::databaseTrafficObserver)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported gateway.audit.durability");
    }

    @Test
    void keepsAuditingOffUnlessTheDeploymentAsksForIt() throws Exception {
        GatewayConfig config = new GatewayConfig();
        ReflectionTestUtils.setField(config, "auditEnabled", false);

        DatabaseTrafficObserver observer = config.databaseTrafficObserver();

        // Off by default: no durable dependency and no path that can deny traffic.
        assertThat(observer.isDeliveryMandatory()).isFalse();
        observer.onEvent(event("select 1"));
        config.destroy();
        assertThat(Files.exists(tempDir.resolve("audit"))).isFalse();
    }

    @Test
    void spoolsMaskedStatementsAndFlushesThemOnShutdown() throws Exception {
        Path spoolDir = tempDir.resolve("audit");
        GatewayConfig config = new GatewayConfig();
        applyAuditFields(config, spoolDir);

        DatabaseTrafficObserver observer = config.databaseTrafficObserver();
        assertThat(observer.isDeliveryMandatory()).isTrue();

        observer.onEvent(event("select * from accounts where id = 4711"));
        // Shutdown must flush what was already accepted.
        config.destroy();

        // Segments are named after the spool, first one numbered 000001.
        List<AuditRecord> records = readRecords(spoolDir.resolve("audit.spool.000001"));
        assertThat(records).hasSize(1);
        String payload = new String(records.get(0).payload(), StandardCharsets.UTF_8);
        assertThat(payload).contains("statement=select").contains("operation=COM_QUERY");
        // Masking runs before the spool: the literal never reaches the trail.
        assertThat(payload).doesNotContain("4711");
    }

    @Test
    void keepsRawStatementsOnlyWhenStatementMaskingIsTurnedOffOnPurpose() throws Exception {
        Path spoolDir = tempDir.resolve("audit-raw");
        GatewayConfig config = new GatewayConfig();
        applyAuditFields(config, spoolDir);
        ReflectionTestUtils.setField(config, "auditMaskStatements", false);

        DatabaseTrafficObserver observer = config.databaseTrafficObserver();
        observer.onEvent(event("select * from accounts where id = 4711"));
        observer.onEvent(event("insert into t values (1)"));
        config.destroy();

        String payload = new String(readRecords(spoolDir.resolve("audit.spool.000001")).get(0).payload(),
                StandardCharsets.UTF_8);
        // Masking is off, so the literal is kept: the deployment asked for raw statements.
        assertThat(payload).contains("4711");
    }


    @Test
    void keepsAllowAllRiskPolicyWhenDenyListsAreEmpty() {
        GatewayConfig config = new GatewayConfig();
        ReflectionTestUtils.setField(config, "proxyDbType", "mysql");
        ReflectionTestUtils.setField(config, "proxyPort", 3307);
        ReflectionTestUtils.setField(config, "targetHost", "127.0.0.1");
        ReflectionTestUtils.setField(config, "targetPort", 3306);
        ReflectionTestUtils.setField(config, "maxConnections", 200);
        ReflectionTestUtils.setField(config, "idleTimeoutSeconds", 0L);
        ReflectionTestUtils.setField(config, "auditEnabled", false);
        ReflectionTestUtils.setField(config, "virtualThreads", false);
        ReflectionTestUtils.setField(config, "rewriteMaxMessageBytes", 1048576);
        ReflectionTestUtils.setField(config, "rewriteMaxHoldMillis", 1000L);
        ReflectionTestUtils.setField(config, "riskDeniedOperations", "");
        ReflectionTestUtils.setField(config, "riskDeniedStatementKeywords", "");

        AbstractProtocolAdapter adapter = (AbstractProtocolAdapter) config.protocolAdapter();
        DatabaseRiskPolicy policy = (DatabaseRiskPolicy) ReflectionTestUtils.getField(adapter, "databaseRiskPolicy");

        RiskDecision decision = policy.evaluate(event("drop table accounts"));
        assertThat(decision.isAllowed()).isTrue();
        config.destroy();
    }

    @Test
    void wiresDenyListRiskPolicyOntoProtocolAdapterFromConfiguration() {
        GatewayConfig config = new GatewayConfig();
        ReflectionTestUtils.setField(config, "proxyDbType", "mysql");
        ReflectionTestUtils.setField(config, "proxyPort", 3307);
        ReflectionTestUtils.setField(config, "targetHost", "127.0.0.1");
        ReflectionTestUtils.setField(config, "targetPort", 3306);
        ReflectionTestUtils.setField(config, "maxConnections", 200);
        ReflectionTestUtils.setField(config, "idleTimeoutSeconds", 0L);
        ReflectionTestUtils.setField(config, "auditEnabled", false);
        ReflectionTestUtils.setField(config, "virtualThreads", false);
        ReflectionTestUtils.setField(config, "rewriteMaxMessageBytes", 1048576);
        ReflectionTestUtils.setField(config, "rewriteMaxHoldMillis", 1000L);
        ReflectionTestUtils.setField(config, "riskDeniedOperations", "");
        ReflectionTestUtils.setField(config, "riskDeniedStatementKeywords", "drop table");

        AbstractProtocolAdapter adapter = (AbstractProtocolAdapter) config.protocolAdapter();
        DatabaseRiskPolicy policy = (DatabaseRiskPolicy) ReflectionTestUtils.getField(adapter, "databaseRiskPolicy");

        assertThat(policy.evaluate(event("select 1")).isAllowed()).isTrue();
        assertThat(policy.evaluate(event("DROP TABLE accounts")).isAllowed()).isFalse();
        config.destroy();
    }

    private static DatabaseTrafficEvent event(String statement) {
        return DatabaseTrafficEvent.builder("MySQL", "session-audit", "COM_QUERY", statement).build();
    }

    /** The audit settings a deployment would provide through configuration. */
    private static void applyAuditFields(GatewayConfig config, Path spoolDir) {
        ReflectionTestUtils.setField(config, "auditEnabled", true);
        ReflectionTestUtils.setField(config, "auditSpoolDir", spoolDir.toString());
        ReflectionTestUtils.setField(config, "auditFileName", "audit.spool");
        ReflectionTestUtils.setField(config, "auditDurability", "strict");
        ReflectionTestUtils.setField(config, "auditBatchSize", 32);
        ReflectionTestUtils.setField(config, "auditMaxBatchDelayMillis", 1L);
        ReflectionTestUtils.setField(config, "auditMaxPendingRecords", 4096);
        ReflectionTestUtils.setField(config, "auditMaxEnqueueWaitMillis", 10L);
        ReflectionTestUtils.setField(config, "auditMaxSpoolBytes", 1024L * 1024);
        ReflectionTestUtils.setField(config, "auditSegmentBytes", 64L * 1024);
        ReflectionTestUtils.setField(config, "auditGradeWrites", true);
        ReflectionTestUtils.setField(config, "auditMaskStatements", true);
    }

    private static List<AuditRecord> readRecords(Path spoolFile) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(spoolFile));
        List<AuditRecord> records = new ArrayList<>();
        while (true) {
            Optional<AuditRecord> record = AuditRecordCodec.decode(buffer);
            if (record.isEmpty()) {
                break;
            }
            records.add(record.get());
        }
        return records;
    }

    @Test
    void wiresPoolSettingsOntoProtocolAdapterWhenEnabled() {
        GatewayConfig config = new GatewayConfig();
        applyMinimalAdapterFields(config);
        ReflectionTestUtils.setField(config, "poolEnabled", true);
        ReflectionTestUtils.setField(config, "poolMaxIdle", 5);
        ReflectionTestUtils.setField(config, "tlsEnabled", false);

        AbstractProtocolAdapter adapter = (AbstractProtocolAdapter) config.protocolAdapter();
        assertThat(adapter.isPoolEnabled()).isTrue();
        assertThat(adapter.getPoolMaxIdle()).isEqualTo(5);
        assertThat(adapter.isClientTlsTerminateEnabled()).isFalse();
        config.destroy();
    }

    @Test
    void wiresTlsTerminatorFromTestKeystoreWhenEnabled() {
        GatewayConfig config = new GatewayConfig();
        applyMinimalAdapterFields(config);
        ReflectionTestUtils.setField(config, "tlsEnabled", true);
        ReflectionTestUtils.setField(config, "tlsKeystorePath", "src/test/resources/tls/gateway-test.p12");
        ReflectionTestUtils.setField(config, "tlsKeystorePassword", "changeit");
        ReflectionTestUtils.setField(config, "tlsKeystoreType", "PKCS12");

        AbstractProtocolAdapter adapter = (AbstractProtocolAdapter) config.protocolAdapter();
        assertThat(adapter.isClientTlsTerminateEnabled()).isTrue();
        config.destroy();
    }

    @Test
    void rejectsTlsEnabledWithoutKeystorePath() {
        GatewayConfig config = new GatewayConfig();
        applyMinimalAdapterFields(config);
        ReflectionTestUtils.setField(config, "tlsEnabled", true);
        ReflectionTestUtils.setField(config, "tlsKeystorePath", "");

        assertThatThrownBy(config::protocolAdapter)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keystore-path");
    }

    @Test
    void rejectsReservedButUnimplementedProxyDbTypesWithClearError() {
        GatewayConfig config = new GatewayConfig();
        applyMinimalAdapterFields(config);
        ReflectionTestUtils.setField(config, "proxyDbType", "oracle");

        assertThatThrownBy(config::protocolAdapter)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oracle");
    }

    private static void applyMinimalAdapterFields(GatewayConfig config) {
        ReflectionTestUtils.setField(config, "proxyDbType", "mysql");
        ReflectionTestUtils.setField(config, "proxyPort", 3307);
        ReflectionTestUtils.setField(config, "targetHost", "127.0.0.1");
        ReflectionTestUtils.setField(config, "targetPort", 3306);
        ReflectionTestUtils.setField(config, "maxConnections", 200);
        ReflectionTestUtils.setField(config, "idleTimeoutSeconds", 0L);
        ReflectionTestUtils.setField(config, "auditEnabled", false);
        ReflectionTestUtils.setField(config, "virtualThreads", false);
        ReflectionTestUtils.setField(config, "rewriteMaxMessageBytes", 1048576);
        ReflectionTestUtils.setField(config, "rewriteMaxHoldMillis", 1000L);
        ReflectionTestUtils.setField(config, "riskDeniedOperations", "");
        ReflectionTestUtils.setField(config, "riskDeniedStatementKeywords", "");
        ReflectionTestUtils.setField(config, "poolEnabled", false);
        ReflectionTestUtils.setField(config, "poolMaxIdle", 8);
        ReflectionTestUtils.setField(config, "tlsEnabled", false);
        ReflectionTestUtils.setField(config, "tlsKeystorePath", "");
        ReflectionTestUtils.setField(config, "tlsKeystorePassword", "");
        ReflectionTestUtils.setField(config, "tlsKeystoreType", "");
        ReflectionTestUtils.setField(config, "tlsKeyAlias", "");
    }
}
