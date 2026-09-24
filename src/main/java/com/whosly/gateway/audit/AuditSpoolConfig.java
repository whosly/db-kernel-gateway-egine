package com.whosly.gateway.audit;
import java.nio.file.Path;
public record AuditSpoolConfig(Path directory, String fileName, AuditDurability durability,
        int batchSize, long maxBatchDelayMillis, int maxPendingRecords, long maxEnqueueWaitMillis,
        long maxSpoolBytes, long segmentBytes) {}
