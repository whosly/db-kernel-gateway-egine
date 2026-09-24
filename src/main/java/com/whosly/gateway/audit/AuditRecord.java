package com.whosly.gateway.audit;
import java.util.Objects;
public final class AuditRecord {
    private final byte[] payload;
    public AuditRecord(byte[] payload) { this.payload = Objects.requireNonNull(payload).clone(); }
    public byte[] payload() { return payload.clone(); }
}
