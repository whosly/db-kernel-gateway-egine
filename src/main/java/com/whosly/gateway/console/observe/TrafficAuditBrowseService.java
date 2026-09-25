package com.whosly.gateway.console.observe;

import com.whosly.gateway.audit.AuditSpoolReader;
import com.whosly.gateway.audit.AuditSpoolReader.ParsedRecord;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.console.sql.InstanceSqlExecuteService;
import com.whosly.gateway.parser.SqlMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Console browse of traffic/gateway audit content (ring + spool + optional JDBC sink).
 *
 * <p>Distinct from control-plane {@code ConsoleAuditStore} (ops actions). Never returns
 * JDBC passwords or masking keys. Statements are truncated and optionally re-masked.</p>
 */
@Service
public class TrafficAuditBrowseService {

    private static final Logger log = LoggerFactory.getLogger(TrafficAuditBrowseService.class);

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    public static final int STATEMENT_MAX = 512;

    private final GatewayConfig gatewayConfig;
    private final RecentTrafficRing recentTrafficRing;
    private final SqlMasker masker;

    @Autowired
    public TrafficAuditBrowseService(GatewayConfig gatewayConfig,
                                     @Autowired(required = false) RecentTrafficRing recentTrafficRing) {
        this(gatewayConfig, recentTrafficRing, SqlMasker.standard());
    }

    public TrafficAuditBrowseService(GatewayConfig gatewayConfig,
                                     RecentTrafficRing recentTrafficRing,
                                     SqlMasker masker) {
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        this.recentTrafficRing = recentTrafficRing;
        this.masker = masker != null ? masker : SqlMasker.standard();
    }

    /**
     * @param sourcePrefer {@code auto}|{@code ring}|{@code spool}|{@code jdbc}
     * @param before       exclusive upper bound on epoch-ms timestamp (newer pages use older cursor)
     */
    public Map<String, Object> browse(int limit,
                                      Long before,
                                      String sourcePrefer,
                                      String protocol,
                                      String operation) {
        int lim = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        String prefer = sourcePrefer == null || sourcePrefer.isBlank()
                ? "auto"
                : sourcePrefer.trim().toLowerCase(Locale.ROOT);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("auditEnabled", gatewayConfig.isAuditEnabled());
        body.put("destination", gatewayConfig.getAuditDestination());
        body.put("maskStatements", gatewayConfig.isAuditMaskStatements());
        body.put("spoolDir", gatewayConfig.getAuditSpoolDir());
        body.put("jdbcConfigured", gatewayConfig.isAuditJdbcConfigured());
        body.put("limit", lim);
        if (before != null) {
            body.put("before", before);
        }

        Resolved resolved = resolve(prefer, lim, before, protocol, operation);
        body.put("source", resolved.source);
        body.put("entries", resolved.entries);
        body.put("count", resolved.entries.size());
        if (resolved.nextBefore != null) {
            body.put("nextBefore", resolved.nextBefore);
        }
        body.put("note", resolved.note);
        body.put("help", "管控操作审计见 GET /console/api/audit；本接口为流量/spool 内容浏览");
        return body;
    }

    private Resolved resolve(String prefer, int lim, Long before, String protocol, String operation) {
        return switch (prefer) {
            case "ring" -> fromRing(lim, before, protocol, operation);
            case "spool" -> fromSpool(lim, before, protocol, operation, true);
            case "jdbc" -> fromJdbc(lim, before, protocol, operation, true);
            default -> auto(lim, before, protocol, operation);
        };
    }

    private Resolved auto(int lim, Long before, String protocol, String operation) {
        if (gatewayConfig.isAuditEnabled()) {
            Resolved spool = fromSpool(lim, before, protocol, operation, false);
            if (!spool.entries.isEmpty() || spool.hadError) {
                return spool;
            }
            if ("jdbc".equalsIgnoreCase(gatewayConfig.getAuditDestination())
                    && gatewayConfig.isAuditJdbcConfigured()) {
                Resolved jdbc = fromJdbc(lim, before, protocol, operation, false);
                if (!jdbc.entries.isEmpty() || jdbc.hadError) {
                    return jdbc;
                }
            }
        }
        return fromRing(lim, before, protocol, operation);
    }

    private Resolved fromRing(int lim, Long before, String protocol, String operation) {
        List<Map<String, Object>> entries = new ArrayList<>();
        Long nextBefore = null;
        if (recentTrafficRing == null) {
            return new Resolved("ring", entries, null,
                    "内存环未启用；开启 gateway.audit 后可浏览 spool", false);
        }
        // Fetch more than limit then filter; ring capacity is small
        int fetch = Math.min(recentTrafficRing.capacity(), Math.max(lim * 3, lim));
        for (RecentTrafficRing.RecentEntry e : recentTrafficRing.recent(null, fetch)) {
            Long ts = e.observedAt() != null ? e.observedAt().toEpochMilli() : null;
            if (before != null && (ts == null || ts >= before)) {
                continue;
            }
            if (!matchProtocolOp(e.protocolName(), e.operation(), protocol, operation)) {
                continue;
            }
            entries.add(sanitizeEntry(
                    ts,
                    e.observedAt() != null ? e.observedAt().toString() : null,
                    e.protocolName(),
                    e.sessionId(),
                    null,
                    e.operation(),
                    e.statement(),
                    e.instanceId(),
                    "ring",
                    null));
            if (ts != null) {
                nextBefore = ts;
            }
            if (entries.size() >= lim) {
                break;
            }
        }
        String note = gatewayConfig.isAuditEnabled()
                ? "来源：进程内 RecentTrafficRing（容量有限，重启丢失）；spool 无数据时回退到环"
                : "流量审计未启用（gateway.audit.enabled=false）；仅显示内存环最近语句（非持久 spool）";
        return new Resolved("ring", entries, nextBefore, note, false);
    }

    private Resolved fromSpool(int lim, Long before, String protocol, String operation, boolean forced) {
        if (!gatewayConfig.isAuditEnabled() && forced) {
            return new Resolved("spool", List.of(), null,
                    "gateway.audit.enabled=false，无 spool 可浏览；可改 source=ring 看内存环", false);
        }
        if (!gatewayConfig.isAuditEnabled()) {
            return new Resolved("spool", List.of(), null, "审计未启用", false);
        }
        Path dir = Path.of(gatewayConfig.getAuditSpoolDir());
        try {
            List<ParsedRecord> raw = AuditSpoolReader.readNewest(
                    dir, gatewayConfig.getAuditFileName(), Math.min(lim * 4, 500), before);
            List<Map<String, Object>> entries = new ArrayList<>();
            Long nextBefore = null;
            for (ParsedRecord r : raw) {
                if (!r.matches(protocol, operation)) {
                    continue;
                }
                entries.add(sanitizeEntry(
                        r.ts(),
                        r.ts() != null ? Instant.ofEpochMilli(r.ts()).toString() : null,
                        r.protocol(),
                        r.sessionId(),
                        r.sequence(),
                        r.operation(),
                        r.statement(),
                        null,
                        "spool",
                        r.segment()));
                if (r.ts() != null) {
                    nextBefore = r.ts();
                }
                if (entries.size() >= lim) {
                    break;
                }
            }
            return new Resolved("spool", entries, nextBefore,
                    "来源：本地 audit spool 分段文件（只读尾部/分页）；仍为 ship-only 的 JDBC sink 搬运路径不变",
                    false);
        } catch (Exception e) {
            log.warn("Failed to read audit spool under {}: {}", dir, e.toString());
            return new Resolved("spool", List.of(), null,
                    "读取 spool 失败：" + e.getClass().getSimpleName() + "（检查目录权限与 gateway.audit.spool-dir）",
                    true);
        }
    }

    private Resolved fromJdbc(int lim, Long before, String protocol, String operation, boolean forced) {
        if (!gatewayConfig.isAuditJdbcConfigured()) {
            return new Resolved("jdbc", List.of(), null,
                    forced
                            ? "未配置 gateway.audit.jdbc.url；默认实验室无需外部库，请用 source=spool|ring"
                            : "JDBC sink 未配置",
                    false);
        }
        String table = gatewayConfig.getAuditJdbcTable();
        if (table == null || table.isBlank() || !table.matches("[A-Za-z0-9_]+")) {
            return new Resolved("jdbc", List.of(), null, "非法 audit jdbc table 名", true);
        }
        // Shipper stores opaque payload strings; order by record_sequence desc as best-effort.
        String sql = "SELECT session_id, record_sequence, payload FROM " + table
                + " ORDER BY record_sequence DESC";
        List<Map<String, Object>> entries = new ArrayList<>();
        Long nextBefore = null;
        try (Connection c = DriverManager.getConnection(
                gatewayConfig.getAuditJdbcUrl(),
                gatewayConfig.getAuditJdbcUsername(),
                gatewayConfig.getAuditJdbcPassword());
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            int scanned = 0;
            while (rs.next() && entries.size() < lim && scanned < lim * 20) {
                scanned++;
                String payload = rs.getString("payload");
                ParsedRecord parsed = AuditSpoolReader.parse(payload, "jdbc", scanned);
                if (before != null && (parsed.ts() == null || parsed.ts() >= before)) {
                    continue;
                }
                if (!parsed.matches(protocol, operation)) {
                    continue;
                }
                long seq = rs.getLong("record_sequence");
                entries.add(sanitizeEntry(
                        parsed.ts(),
                        parsed.ts() != null ? Instant.ofEpochMilli(parsed.ts()).toString() : null,
                        parsed.protocol(),
                        parsed.sessionId() != null ? parsed.sessionId() : rs.getString("session_id"),
                        seq,
                        parsed.operation(),
                        parsed.statement(),
                        null,
                        "jdbc",
                        null));
                if (parsed.ts() != null) {
                    nextBefore = parsed.ts();
                }
            }
            return new Resolved("jdbc", entries, nextBefore,
                    "来源：gateway.audit.destination=jdbc 独立审计库（只读浏览；密码永不回传）",
                    false);
        } catch (Exception e) {
            log.warn("Failed to browse JDBC audit sink: {}", e.toString());
            return new Resolved("jdbc", List.of(), null,
                    "读取 JDBC 审计表失败：" + e.getClass().getSimpleName()
                            + "（表须已建，见 docs/sql/audit-sink-schema.sql）",
                    true);
        }
    }

    private Map<String, Object> sanitizeEntry(Long ts,
                                              String observedAt,
                                              String protocol,
                                              String sessionId,
                                              Long sequence,
                                              String operation,
                                              String statement,
                                              String instanceId,
                                              String source,
                                              String segment) {
        String stmt = statement == null ? "" : statement;
        if (gatewayConfig.isAuditMaskStatements()) {
            stmt = masker.mask(stmt);
        }
        stmt = InstanceSqlExecuteService.truncateForAudit(stmt, STATEMENT_MAX);
        // Defensive: never echo obvious password-like tokens from detail blobs
        stmt = redactSecretish(stmt);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", ts);
        m.put("observedAt", observedAt);
        m.put("protocolName", protocol);
        m.put("sessionId", sessionId);
        m.put("sequence", sequence);
        m.put("operation", operation);
        m.put("statement", stmt);
        m.put("instanceId", instanceId);
        m.put("source", source);
        if (segment != null) {
            m.put("segment", segment);
        }
        return m;
    }

    static String redactSecretish(String statement) {
        if (statement == null || statement.isEmpty()) {
            return "";
        }
        // Soft redact common password / secret key assignments if they survived masking
        return statement
                .replaceAll("(?i)(password\\s*=\\s*)([^\\s,;]+)", "$1***")
                .replaceAll("(?i)(pwd\\s*=\\s*)([^\\s,;]+)", "$1***")
                .replaceAll("(?i)(secret\\s*=\\s*)([^\\s,;]+)", "$1***")
                .replaceAll("(?i)(identified\\s+by\\s+)(\\S+)", "$1***");
    }

    private static boolean matchProtocolOp(String protocol, String operation,
                                           String protocolFilter, String operationFilter) {
        if (protocolFilter != null && !protocolFilter.isBlank()) {
            if (protocol == null || !protocol.equalsIgnoreCase(protocolFilter.trim())) {
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

    private record Resolved(
            String source,
            List<Map<String, Object>> entries,
            Long nextBefore,
            String note,
            boolean hadError
    ) {
    }
}
