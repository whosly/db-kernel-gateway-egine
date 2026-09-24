package com.whosly.gateway.audit;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Segmented local audit spool with binary length-prefixed records. */
public final class AuditSpool implements AutoCloseable {

    private final AuditSpoolConfig config;
    private final Object lock = new Object();
    private final AtomicInteger segmentCounter = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicLong> sequencesBySession = new ConcurrentHashMap<>();
    private Path activeFile;
    private OutputStream output;
    private long bytesInSegment;

    public AuditSpool(AuditSpoolConfig config) throws IOException {
        this.config = Objects.requireNonNull(config);
        Files.createDirectories(config.directory());
        rotateSegment();
    }

    public void append(DatabaseTrafficEvent event) throws IOException {
        long sequence = sequencesBySession
                .computeIfAbsent(event.getSessionId(), id -> new AtomicLong())
                .getAndIncrement();
        String payload = "ts=" + System.currentTimeMillis()
                + " protocol=" + event.getProtocolName()
                + " session=" + event.getSessionId()
                + " sequence=" + sequence
                + " operation=" + event.getOperation()
                + " statement=" + (event.getStatement() == null ? "" : event.getStatement());
        byte[] framed = AuditRecordCodec.encode(new AuditRecord(payload.getBytes(StandardCharsets.UTF_8)));
        synchronized (lock) {
            if (bytesInSegment + framed.length > config.segmentBytes() && bytesInSegment > 0) {
                rotateSegment();
            }
            if (bytesInSegment + framed.length > config.maxSpoolBytes()) {
                throw new IOException("Audit spool capacity exceeded");
            }
            output.write(framed);
            bytesInSegment += framed.length;
            if (config.durability() == AuditDurability.STRICT) {
                output.flush();
            }
        }
    }

    public Path activeFile() {
        synchronized (lock) {
            return activeFile;
        }
    }

    private void rotateSegment() throws IOException {
        if (output != null) {
            output.flush();
            output.close();
        }
        int segment = segmentCounter.incrementAndGet();
        activeFile = config.directory().resolve(config.fileName() + String.format(".%06d", segment));
        output = Files.newOutputStream(activeFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        bytesInSegment = Files.size(activeFile);
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                if (output != null) {
                    output.flush();
                    output.close();
                }
            } catch (IOException ignored) {
            }
            output = null;
        }
    }
}
