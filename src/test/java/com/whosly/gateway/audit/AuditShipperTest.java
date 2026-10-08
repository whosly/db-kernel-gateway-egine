package com.whosly.gateway.audit;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class AuditShipperTest {

    @TempDir
    Path tempDir;

    @Test
    void shipsSpoolRecordsToDestinationAndAdvancesOffsetOnClose() throws Exception {
        Path spoolDir = tempDir.resolve("ship");
        AuditSpool spool = new AuditSpool(new AuditSpoolConfig(
                spoolDir, "audit.spool", AuditDurability.STRICT,
                32, 1L, 4096, 10L, 1024 * 1024, 64 * 1024));
        spool.append(DatabaseTrafficEvent.builder("MySQL", "s-ship", "COM_QUERY", "select 1").build());
        spool.append(DatabaseTrafficEvent.builder("MySQL", "s-ship", "COM_QUERY", "select 2").build());

        RecordingDestination destination = new RecordingDestination();
        Path offsetFile = spoolDir.resolve("audit.spool.offset");
        AuditShipper shipper = new AuditShipper(spool, destination, new AuditShippingOffset(offsetFile), 10, 50);
        shipper.start();

        // close() triggers a final shipOnce after stopping the scheduler.
        shipper.close();
        spool.close();

        assertThat(destination.batches).isNotEmpty();
        List<String> all = destination.batches.stream().flatMap(List::stream).toList();
        assertThat(all).anyMatch(line -> line.contains("statement=select 1"));
        assertThat(all).anyMatch(line -> line.contains("statement=select 2"));
        assertThat(new AuditShippingOffset(offsetFile).get()).isEqualTo(all.size());
    }

    @Test
    void resumesFromPersistedOffsetWithoutResending() throws Exception {
        Path spoolDir = tempDir.resolve("resume");
        AuditSpool spool = new AuditSpool(new AuditSpoolConfig(
                spoolDir, "audit.spool", AuditDurability.STRICT,
                32, 1L, 4096, 10L, 1024 * 1024, 64 * 1024));
        spool.append(DatabaseTrafficEvent.builder("MySQL", "s-resume", "COM_QUERY", "first").build());
        spool.append(DatabaseTrafficEvent.builder("MySQL", "s-resume", "COM_QUERY", "second").build());

        Path offsetFile = spoolDir.resolve("audit.spool.offset");
        new AuditShippingOffset(offsetFile).set(1); // first record already shipped

        RecordingDestination destination = new RecordingDestination();
        AuditShipper shipper = new AuditShipper(spool, destination, new AuditShippingOffset(offsetFile), 10, 50);
        shipper.close(); // final ship without starting scheduler
        spool.close();

        List<String> all = destination.batches.stream().flatMap(List::stream).toList();
        assertThat(all).hasSize(1);
        assertThat(all.get(0)).contains("statement=second");
        assertThat(all.get(0)).doesNotContain("statement=first");
    }

    private static final class RecordingDestination implements AuditDestination {
        private final List<List<String>> batches = new CopyOnWriteArrayList<>();

        @Override
        public void writeBatch(List<String> records) {
            batches.add(new ArrayList<>(records));
        }
    }
}
