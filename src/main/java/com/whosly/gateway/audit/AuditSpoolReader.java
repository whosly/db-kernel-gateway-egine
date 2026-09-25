package com.whosly.gateway.audit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only helper for length-prefixed audit spool segments.
 *
 * <p>Does not mutate or truncate spool files. Incomplete trailing frames are
 * skipped (same as {@link AuditShipper}). Payload text is parsed into fields
 * without echoing secrets beyond the statement field already stored.</p>
 */
public final class AuditSpoolReader {

    private static final Pattern FIELD = Pattern.compile(
            "^(?:ts=(\\d+)\\s+)?(?:protocol=(\\S+)\\s+)?(?:session=(\\S+)\\s+)?(?:sequence=(\\d+)\\s+)?"
                    + "(?:operation=(\\S+)\\s+)?(?:statement=(.*))?$",
            Pattern.DOTALL);

    private AuditSpoolReader() {
    }

    /**
     * Lists spool segment files for {@code fileName} prefix, newest segment last
     * (by numeric suffix then mtime).
     */
    public static List<Path> listSegmentFiles(Path directory, String fileName) throws IOException {
        Objects.requireNonNull(directory, "directory");
        String prefix = (fileName == null || fileName.isBlank()) ? "audit.spool" : fileName.trim();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, prefix + ".*")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.endsWith(".offset")) {
                    continue;
                }
                if (Files.isRegularFile(p)) {
                    files.add(p);
                }
            }
        }
        files.sort(Comparator
                .comparingInt((Path p) -> segmentIndex(p.getFileName().toString(), prefix))
                .thenComparing(Path::toString));
        return List.copyOf(files);
    }

    /**
     * Reads all complete records from one segment file (oldest-first within file).
     */
    public static List<ParsedRecord> readSegment(Path spoolFile) throws IOException {
        Objects.requireNonNull(spoolFile, "spoolFile");
        if (!Files.isRegularFile(spoolFile)) {
            return List.of();
        }
        byte[] bytes = Files.readAllBytes(spoolFile);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        List<ParsedRecord> out = new ArrayList<>();
        String segment = spoolFile.getFileName().toString();
        int indexInFile = 0;
        while (true) {
            Optional<AuditRecord> record = AuditRecordCodec.decode(buffer);
            if (record.isEmpty()) {
                break;
            }
            String raw = new String(record.get().payload(), StandardCharsets.UTF_8);
            out.add(parse(raw, segment, indexInFile++));
        }
        return List.copyOf(out);
    }

    /**
     * Newest-first browse across all segments with optional {@code beforeTs} cursor
     * (exclusive: keep records with {@code ts < beforeTs}).
     */
    public static List<ParsedRecord> readNewest(Path directory, String fileName, int limit, Long beforeTs)
            throws IOException {
        int lim = Math.max(0, Math.min(limit, 500));
        if (lim == 0) {
            return List.of();
        }
        List<Path> segments = listSegmentFiles(directory, fileName);
        List<ParsedRecord> collected = new ArrayList<>();
        // Walk segments newest → oldest
        for (int s = segments.size() - 1; s >= 0 && collected.size() < lim; s--) {
            List<ParsedRecord> inFile = readSegment(segments.get(s));
            for (int i = inFile.size() - 1; i >= 0 && collected.size() < lim; i--) {
                ParsedRecord r = inFile.get(i);
                if (beforeTs != null && r.ts() != null && r.ts() >= beforeTs) {
                    continue;
                }
                if (beforeTs != null && r.ts() == null) {
                    continue;
                }
                collected.add(r);
            }
        }
        return List.copyOf(collected);
    }

    public static ParsedRecord parse(String raw, String segment, int indexInFile) {
        String text = raw == null ? "" : raw;
        Matcher m = FIELD.matcher(text);
        Long ts = null;
        String protocol = null;
        String session = null;
        Long sequence = null;
        String operation = null;
        String statement = text;
        if (m.matches()) {
            if (m.group(1) != null) {
                try {
                    ts = Long.parseLong(m.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            protocol = emptyToNull(m.group(2));
            session = emptyToNull(m.group(3));
            if (m.group(4) != null) {
                try {
                    sequence = Long.parseLong(m.group(4));
                } catch (NumberFormatException ignored) {
                }
            }
            operation = emptyToNull(m.group(5));
            statement = m.group(6) != null ? m.group(6) : "";
        }
        return new ParsedRecord(ts, protocol, session, sequence, operation, statement, text, segment, indexInFile);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static int segmentIndex(String name, String prefix) {
        String suffix = name.startsWith(prefix + ".") ? name.substring(prefix.length() + 1) : name;
        try {
            return Integer.parseInt(suffix);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * One decoded spool line, safe for console JSON (caller still truncates/masks statement).
     */
    public record ParsedRecord(
            Long ts,
            String protocol,
            String sessionId,
            Long sequence,
            String operation,
            String statement,
            String raw,
            String segment,
            int indexInFile
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", ts);
            m.put("observedAt", ts != null ? java.time.Instant.ofEpochMilli(ts).toString() : null);
            m.put("protocolName", protocol);
            m.put("sessionId", sessionId);
            m.put("sequence", sequence);
            m.put("operation", operation);
            m.put("statement", statement);
            m.put("segment", segment);
            m.put("source", "spool");
            return m;
        }

        public boolean matches(String protocolFilter, String operationFilter) {
            if (protocolFilter != null && !protocolFilter.isBlank()) {
                if (protocol == null
                        || !protocol.equalsIgnoreCase(protocolFilter.trim())) {
                    return false;
                }
            }
            if (operationFilter != null && !operationFilter.isBlank()) {
                if (operation == null
                        || !operation.toLowerCase(Locale.ROOT)
                        .contains(operationFilter.trim().toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
            return true;
        }
    }
}
