package com.whosly.gateway.audit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
public final class AuditShippingOffset {
    private final Path file;
    private long offset;
    public AuditShippingOffset(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!content.isEmpty()) offset = Long.parseLong(content);
            } catch (Exception ignored) { offset = 0L; }
        }
    }
    public long get() { return offset; }
    public void set(long value) throws IOException {
        this.offset = value;
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, Long.toString(value), StandardCharsets.UTF_8);
    }
}
