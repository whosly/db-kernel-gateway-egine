package com.whosly.gateway.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Periodically ships spool records to a final destination. */
public final class AuditShipper implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AuditShipper.class);

    private final AuditSpool spool;
    private final AuditDestination destination;
    private final AuditShippingOffset offset;
    private final int batchSize;
    private final long intervalMillis;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "audit-shipper");
                t.setDaemon(true);
                return t;
            });

    public AuditShipper(AuditSpool spool, AuditDestination destination, AuditShippingOffset offset,
                        int batchSize, long intervalMillis) {
        this.spool = spool;
        this.destination = destination;
        this.offset = offset;
        this.batchSize = Math.max(1, batchSize);
        this.intervalMillis = Math.max(1L, intervalMillis);
    }

    public void start() {
        running.set(true);
        scheduler.scheduleWithFixedDelay(this::shipSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public boolean isRunning() {
        return running.get() && !scheduler.isShutdown();
    }

    private void shipSafely() {
        try {
            shipOnce();
        } catch (Exception e) {
            log.warn("Audit shipper cycle failed: {}", e.toString());
        }
    }

    private void shipOnce() throws Exception {
        if (spool.activeFile() == null || !Files.isRegularFile(spool.activeFile())) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(spool.activeFile()));
        List<String> batch = new ArrayList<>();
        long seen = 0;
        long start = offset.get();
        while (true) {
            Optional<AuditRecord> record = AuditRecordCodec.decode(buffer);
            if (record.isEmpty()) {
                break;
            }
            if (seen++ < start) {
                continue;
            }
            batch.add(new String(record.get().payload()));
            if (batch.size() >= batchSize) {
                break;
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        destination.writeBatch(batch);
        offset.set(start + batch.size());
    }

    @Override
    public void close() {
        running.set(false);
        scheduler.shutdownNow();
        try {
            shipOnce();
        } catch (Exception e) {
            log.debug("Final audit ship failed: {}", e.toString());
        }
    }
}
