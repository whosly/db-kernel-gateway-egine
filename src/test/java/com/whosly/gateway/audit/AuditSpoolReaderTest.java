package com.whosly.gateway.audit;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditSpoolReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsNewestAcrossSegmentsWithBeforeCursor() throws Exception {
        Path spoolDir = tempDir.resolve("spool");
        AuditSpool spool = new AuditSpool(new AuditSpoolConfig(
                spoolDir, "audit.spool", AuditDurability.STRICT,
                32, 1L, 4096, 10L, 1024 * 1024, 40));
        spool.append(event("s1", "select 1"));
        Thread.sleep(2);
        spool.append(event("s1", "select 2"));
        Path first = spool.activeFile();
        spool.append(event("s1", "select 3"));
        spool.close();

        List<AuditSpoolReader.ParsedRecord> all =
                AuditSpoolReader.readNewest(spoolDir, "audit.spool", 10, null);
        assertThat(all).isNotEmpty();
        assertThat(all.get(0).statement()).contains("select");
        assertThat(all).allSatisfy(r -> assertThat(r.ts()).isNotNull());

        Long newestTs = all.get(0).ts();
        List<AuditSpoolReader.ParsedRecord> older =
                AuditSpoolReader.readNewest(spoolDir, "audit.spool", 10, newestTs);
        assertThat(older).allSatisfy(r -> assertThat(r.ts()).isLessThan(newestTs));

        assertThat(AuditSpoolReader.listSegmentFiles(spoolDir, "audit.spool")).isNotEmpty();
        assertThat(first.getFileName().toString()).startsWith("audit.spool.");
    }

    @Test
    void parseExtractsFieldsEvenWhenStatementHasEquals() {
        String raw = "ts=100 protocol=MySQL session=abc sequence=2 operation=COM_QUERY "
                + "statement=select * from t where a=1";
        AuditSpoolReader.ParsedRecord r = AuditSpoolReader.parse(raw, "seg", 0);
        assertThat(r.ts()).isEqualTo(100L);
        assertThat(r.protocol()).isEqualTo("MySQL");
        assertThat(r.sessionId()).isEqualTo("abc");
        assertThat(r.sequence()).isEqualTo(2L);
        assertThat(r.operation()).isEqualTo("COM_QUERY");
        assertThat(r.statement()).isEqualTo("select * from t where a=1");
    }

    private static DatabaseTrafficEvent event(String sessionId, String statement) {
        return DatabaseTrafficEvent.builder("MySQL", sessionId, "COM_QUERY", statement).build();
    }
}
