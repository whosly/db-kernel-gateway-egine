package com.whosly.gateway.audit;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficObserver;
import com.whosly.gateway.config.GatewayConfig;
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

class SpoolingTrafficObserverTest {

    @TempDir
    Path tempDir;

    @Test
    void happyPathAppendsThroughSpoolingObserverWhenAuditEnabled() throws Exception {
        Path spoolDir = tempDir.resolve("observer");
        AuditSpool spool = new AuditSpool(new AuditSpoolConfig(
                spoolDir, "audit.spool", AuditDurability.STRICT,
                32, 1L, 4096, 10L, 1024 * 1024, 64 * 1024));
        SpoolingTrafficObserver observer = new SpoolingTrafficObserver(spool, null);

        assertThat(observer.isDeliveryMandatory()).isTrue();
        observer.onEvent(DatabaseTrafficEvent.builder("PostgreSQL", "pg-1", "Query", "select now()").build());
        spool.close();

        List<AuditRecord> records = readRecords(spoolDir.resolve("audit.spool.000001"));
        assertThat(records).hasSize(1);
        assertThat(new String(records.get(0).payload(), StandardCharsets.UTF_8))
                .contains("protocol=PostgreSQL")
                .contains("operation=Query")
                .contains("statement=select now()");
    }

    @Test
    void gatewayConfigReturnsNoopObserverWhenAuditDisabled() throws Exception {
        GatewayConfig config = new GatewayConfig();
        ReflectionTestUtils.setField(config, "auditEnabled", false);

        DatabaseTrafficObserver observer = config.databaseTrafficObserver();

        assertThat(observer.isDeliveryMandatory()).isFalse();
        observer.onEvent(DatabaseTrafficEvent.builder("MySQL", "noop", "COM_QUERY", "select 1").build());
        config.destroy();
        assertThat(Files.exists(tempDir.resolve("audit"))).isFalse();
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
