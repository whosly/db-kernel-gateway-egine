package com.whosly.gateway.audit;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditSpoolTest {

    @TempDir
    Path tempDir;

    @Test
    void appendsEventsAsLengthPrefixedRecordsAndFlushesOnClose() throws Exception {
        Path spoolDir = tempDir.resolve("spool");
        AuditSpool spool = new AuditSpool(config(spoolDir, 1024 * 1024, 64 * 1024));

        spool.append(event("s1", "select 1"));
        spool.append(event("s1", "select 2"));
        Path active = spool.activeFile();
        spool.close();

        assertThat(active.getFileName().toString()).isEqualTo("audit.spool.000001");
        List<AuditRecord> records = readRecords(active);
        assertThat(records).hasSize(2);
        String first = new String(records.get(0).payload(), StandardCharsets.UTF_8);
        String second = new String(records.get(1).payload(), StandardCharsets.UTF_8);
        assertThat(first).contains("session=s1").contains("sequence=0").contains("statement=select 1");
        assertThat(second).contains("sequence=1").contains("statement=select 2");
    }

    @Test
    void rotatesWhenSegmentCapacityIsExceeded() throws Exception {
        Path spoolDir = tempDir.resolve("rotate");
        // Tiny segment so the second append forces rotation.
        AuditSpool spool = new AuditSpool(config(spoolDir, 1024 * 1024, 40));

        spool.append(event("s-rotate", "aaaaaaaaaaaaaaaa"));
        Path first = spool.activeFile();
        spool.append(event("s-rotate", "bbbbbbbbbbbbbbbb"));
        Path second = spool.activeFile();
        spool.close();

        assertThat(first.getFileName().toString()).isEqualTo("audit.spool.000001");
        assertThat(second.getFileName().toString()).isEqualTo("audit.spool.000002");
        assertThat(readRecords(first)).isNotEmpty();
        assertThat(readRecords(second)).isNotEmpty();
    }

    @Test
    void rejectsAppendWhenMaxSpoolBytesWouldBeExceeded() throws Exception {
        Path spoolDir = tempDir.resolve("cap");
        AuditSpool spool = new AuditSpool(config(spoolDir, 32, 1024 * 1024));

        assertThatThrownBy(() -> spool.append(event("s-cap", "this statement is definitely too long for thirty two bytes")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Audit spool capacity exceeded");
        spool.close();
    }

    private static AuditSpoolConfig config(Path directory, long maxSpoolBytes, long segmentBytes) {
        return new AuditSpoolConfig(directory, "audit.spool", AuditDurability.STRICT,
                32, 1L, 4096, 10L, maxSpoolBytes, segmentBytes);
    }

    private static DatabaseTrafficEvent event(String sessionId, String statement) {
        return DatabaseTrafficEvent.builder("MySQL", sessionId, "COM_QUERY", statement).build();
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
