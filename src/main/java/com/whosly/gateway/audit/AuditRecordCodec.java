package com.whosly.gateway.audit;
import java.nio.ByteBuffer;
import java.util.Optional;
public final class AuditRecordCodec {
    private AuditRecordCodec() {}
    public static byte[] encode(AuditRecord record) {
        byte[] payload = record.payload();
        ByteBuffer buffer = ByteBuffer.allocate(4 + payload.length);
        buffer.putInt(payload.length);
        buffer.put(payload);
        return buffer.array();
    }
    public static Optional<AuditRecord> decode(ByteBuffer buffer) {
        if (buffer.remaining() < 4) return Optional.empty();
        buffer.mark();
        int length = buffer.getInt();
        if (length < 0 || buffer.remaining() < length) { buffer.reset(); return Optional.empty(); }
        byte[] payload = new byte[length];
        buffer.get(payload);
        return Optional.of(new AuditRecord(payload));
    }
}
