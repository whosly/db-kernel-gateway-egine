package com.whosly.gateway.config;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
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
        ReflectionTestUtils.setField(config, "proxyDbType", "oracle");

        assertThatThrownBy(config::protocolAdapter)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported gateway proxy database protocol");
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
        ReflectionTestUtils.setField(config, "auditEnabled", true);
        ReflectionTestUtils.setField(config, "auditSpoolDir", spoolDir.toString());
        ReflectionTestUtils.setField(config, "auditFileName", "audit.spool");
        ReflectionTestUtils.setField(config, "auditDurability", "strict");
        ReflectionTestUtils.setField(config, "auditBatchSize", 32);
        ReflectionTestUtils.setField(config, "auditMaxBatchDelayMillis", 1L);
        ReflectionTestUtils.setField(config, "auditMaxPendingRecords", 4096);
        ReflectionTestUtils.setField(config, "auditMaxEnqueueWaitMillis", 10L);
        ReflectionTestUtils.setField(config, "auditMaxSpoolBytes", 1024L * 1024);
        ReflectionTestUtils.setField(config, "auditGradeWrites", true);

        DatabaseTrafficObserver observer = config.databaseTrafficObserver();
        assertThat(observer.isDeliveryMandatory()).isTrue();

        observer.onEvent(event("select * from accounts where id = 4711"));
        // Shutdown must flush what was already accepted.
        config.destroy();

        List<AuditRecord> records = readRecords(spoolDir.resolve("audit.spool"));
        assertThat(records).hasSize(1);
        String payload = new String(records.get(0).payload(), StandardCharsets.UTF_8);
        assertThat(payload).contains("statement=select").contains("operation=COM_QUERY");
        // Masking runs before the spool: the literal never reaches the trail.
        assertThat(payload).doesNotContain("4711");
    }

    private static DatabaseTrafficEvent event(String statement) {
        return DatabaseTrafficEvent.builder("MySQL", "session-audit", "COM_QUERY", statement).build();
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
}
