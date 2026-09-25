package com.whosly.gateway.console.sql;

import com.whosly.gateway.adapter.protocol.DatabaseRiskPolicy;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.MutableDatabaseRiskPolicy;
import com.whosly.gateway.adapter.protocol.RiskDecision;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.console.persist.ConsoleInstanceRecord;
import com.whosly.gateway.console.persist.ConsoleInstanceStore;
import com.whosly.gateway.console.schema.InstanceSchemaColumnsService;
import com.whosly.gateway.console.schema.InstanceSchemaColumnsService.SchemaConnectException;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import com.whosly.gateway.runtime.GatewayListenerRuntime.ManagedListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Console SQL workspace: for wire-protocol types (MySQL / PostgreSQL / MariaDB /
 * SQL Server), JDBC connects to the instance <em>proxy listen port</em> so masking,
 * traffic observation, and data-plane risk apply. Schema-columns discovery remains
 * direct target JDBC.
 *
 * <p>Exception: {@code h2} lab/unit tests keep direct JDBC (no ProtocolAdapter wire
 * path in product).</p>
 */
@Service
public class InstanceSqlExecuteService {

    private static final Logger log = LoggerFactory.getLogger(InstanceSqlExecuteService.class);
    private static final int DEFAULT_MAX_ROWS = 200;
    private static final int CAP_MAX_ROWS = 1000;
    private static final int DEFAULT_TIMEOUT_MS = 15_000;
    private static final int CAP_TIMEOUT_MS = 60_000;
    private static final int CELL_MAX_CHARS = 4096;
    private static final Pattern MID_SEMICOLON = Pattern.compile(";\\s*\\S", Pattern.DOTALL);

    private final GatewayListenerRuntime listenerRuntime;
    private final ConsoleInstanceStore consoleStore; // nullable
    private final GatewayConfig gatewayConfig;
    private final MutableDatabaseRiskPolicy riskPolicy; // nullable

    public InstanceSqlExecuteService(GatewayListenerRuntime listenerRuntime,
                                     Optional<ConsoleInstanceStore> consoleStore,
                                     GatewayConfig gatewayConfig,
                                     Optional<MutableDatabaseRiskPolicy> riskPolicy) {
        this.listenerRuntime = Objects.requireNonNull(listenerRuntime, "listenerRuntime");
        this.consoleStore = consoleStore.orElse(null);
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        this.riskPolicy = riskPolicy.orElse(null);
    }

    public Map<String, Object> execute(String instanceId, String sql, Integer maxRows, Integer timeoutMs) {
        ManagedListener listener = listenerRuntime.find(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown gateway instance id: " + instanceId));

        String statement = sql == null ? "" : sql.trim();
        if (statement.isEmpty()) {
            throw new IllegalArgumentException("sql is required");
        }
        validateSingleStatement(statement);

        int rowsCap = clamp(maxRows, DEFAULT_MAX_ROWS, 1, CAP_MAX_ROWS);
        int timeout = clamp(timeoutMs, DEFAULT_TIMEOUT_MS, 1000, CAP_TIMEOUT_MS);
        int queryTimeoutSec = Math.max(1, (int) Math.ceil(timeout / 1000.0));

        applyRiskPolicy(listener, statement);

        TargetCreds creds = resolveCreds(listener);
        String typeForCred = listener.dbType() != null ? listener.dbType().toLowerCase(Locale.ROOT) : "";
        if (!"h2".equals(typeForCred) && !hasText(creds.password())) {
            throw new IllegalArgumentException("实例未配置目标密码，请先编辑实例写入凭据后再执行 SQL");
        }

        String dbType = listener.dbType();
        assertJdbcSupported(dbType);

        // SQL Server: only if driver present
        if (isSqlServer(dbType) && !isDriverPresent("com.microsoft.sqlserver.jdbc.SQLServerDriver")) {
            throw new IllegalArgumentException(
                    "当前 classpath 无 SQL Server JDBC 驱动（mssql-jdbc），无法在管控台经代理执行 SQL；请确认依赖已打包。线协议类型可用：mysql / postgresql / sqlserver");
        }

        boolean viaProxy = usesWireProxy(dbType);
        String jdbcUrl;
        String proxyHost = null;
        int proxyPort = -1;
        if (viaProxy) {
            if (!listener.isRunning()) {
                throw new IllegalArgumentException(
                        "实例未启动，请先在「网关实例」页启动后再执行 SQL（经代理监听口；可验证脱敏/观测）");
            }
            proxyHost = resolveProxyConnectHost(listener.listenHost());
            proxyPort = listener.listenPort();
            try {
                jdbcUrl = buildProxyJdbcUrl(dbType, listener.listenHost(), proxyPort, creds.database());
            } catch (SchemaConnectException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        } else {
            try {
                jdbcUrl = InstanceSchemaColumnsService.buildJdbcUrl(
                        dbType, creds.host(), creds.port(), creds.database());
            } catch (SchemaConnectException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }

        Properties props = new Properties();
        if (hasText(creds.username())) {
            props.setProperty("user", creds.username());
        }
        if (creds.password() != null) {
            props.setProperty("password", creds.password());
        }
        props.setProperty("connectTimeout", String.valueOf(Math.min(timeout, 10_000)));
        props.setProperty("loginTimeout", String.valueOf(Math.min(queryTimeoutSec, 10)));

        long started = System.nanoTime();
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        int updateCount = -1;
        List<String> warnings = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(jdbcUrl, props);
             Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(rowsCap + 1);
            stmt.setQueryTimeout(queryTimeoutSec);
            boolean hasResult = stmt.execute(statement);
            if (hasResult) {
                try (ResultSet rs = stmt.getResultSet()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    for (int i = 1; i <= colCount; i++) {
                        String label = meta.getColumnLabel(i);
                        if (label == null || label.isBlank()) {
                            label = meta.getColumnName(i);
                        }
                        columns.add(label != null ? label : ("col" + i));
                    }
                    while (rs.next()) {
                        if (rows.size() >= rowsCap) {
                            truncated = true;
                            break;
                        }
                        List<Object> row = new ArrayList<>(colCount);
                        for (int i = 1; i <= colCount; i++) {
                            row.add(cellValue(rs, i, meta.getColumnType(i)));
                        }
                        rows.add(row);
                    }
                }
            } else {
                updateCount = stmt.getUpdateCount();
                warnings.add("非查询语句，影响行数=" + updateCount);
            }
        } catch (SQLException e) {
            log.warn("SQL execute failed for instance '{}': {}", instanceId, e.getMessage());
            throw new SchemaConnectException("连接或执行失败：" + e.getMessage(), e);
        }

        long durationMs = (System.nanoTime() - started) / 1_000_000L;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("columns", columns);
        body.put("rows", rows);
        body.put("rowCount", rows.size());
        body.put("truncated", truncated);
        body.put("durationMs", durationMs);
        body.put("instanceId", listener.id());
        body.put("dbType", listener.dbType());
        body.put("viaProxy", viaProxy);
        if (viaProxy) {
            body.put("proxyHost", proxyHost);
            body.put("proxyPort", proxyPort);
            body.put("note", "经实例代理监听口执行，可验证脱敏/观测/风控数据面");
        } else {
            body.put("note", "h2 lab：直连目标 JDBC（无协议代理适配器）；生产库类型经 listenPort 代理");
        }
        if (updateCount >= 0) {
            body.put("updateCount", updateCount);
        }
        if (!warnings.isEmpty()) {
            body.put("warnings", warnings);
        }
        body.put("message", truncated
                ? "结果已截断至 maxRows=" + rowsCap
                : "执行成功");
        return body;
    }

    /**
     * JDBC URL through the proxy listen socket. Host prefers loopback when the
     * listener binds {@code 0.0.0.0} / {@code ::} / {@code *}; otherwise uses the
     * concrete bind host. Port is {@link ManagedListener#listenPort()}; database
     * name is the <em>target</em> database (transparent proxy).
     *
     * <p>Package-visible for tests.</p>
     */
    public static String buildProxyJdbcUrl(String dbType, String listenHost, int listenPort, String database) {
        return InstanceSchemaColumnsService.buildJdbcUrl(
                dbType, resolveProxyConnectHost(listenHost), listenPort, database);
    }

    /**
     * Map listener bind address to a JDBC connect host. Wildcard / all-interfaces
     * binds resolve to {@code 127.0.0.1} so the console process can dial the local
     * proxy socket.
     *
     * <p>Package-visible for tests.</p>
     */
    static String resolveProxyConnectHost(String listenHost) {
        String h = listenHost == null ? "" : listenHost.trim();
        if (h.isEmpty()
                || "0.0.0.0".equals(h)
                || "*".equals(h)
                || "::".equals(h)
                || "[::]".equals(h)
                || "0:0:0:0:0:0:0:0".equals(h)) {
            return "127.0.0.1";
        }
        return h;
    }

    /** Wire types that have a ProtocolAdapter listen path (not h2 lab). */
    static boolean usesWireProxy(String dbType) {
        String type = dbType != null ? dbType.toLowerCase(Locale.ROOT).trim() : "";
        return switch (type) {
            case "mysql", "mariadb", "postgresql", "postgres", "sqlserver", "mssql" -> true;
            default -> false;
        };
    }

    /** Package-visible for tests. */
    public static void validateSingleStatement(String sql) {
        String trimmed = sql.trim();
        // strip one trailing semicolon
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        if (MID_SEMICOLON.matcher(trimmed).find()) {
            throw new IllegalArgumentException("仅支持单条 SQL 语句（不允许中间分号）；请去掉多余语句后再执行");
        }
    }

    public static String firstKeyword(String sql) {
        String s = sql.trim();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        int j = i;
        while (j < s.length() && (Character.isLetter(s.charAt(j)) || s.charAt(j) == '_')) {
            j++;
        }
        if (j == i) {
            return "SQL";
        }
        return s.substring(i, j).toUpperCase(Locale.ROOT);
    }

    public static String truncateForAudit(String sql, int max) {
        if (sql == null) {
            return "";
        }
        String s = sql.replaceAll("\\s+", " ").trim();
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    private void applyRiskPolicy(ManagedListener listener, String statement) {
        DatabaseRiskPolicy policy = riskPolicy != null ? riskPolicy : DatabaseRiskPolicy.allowAll();
        String op = firstKeyword(statement);
        DatabaseTrafficEvent event = DatabaseTrafficEvent.builder(
                "ConsoleSQL",
                "console-sql-" + listener.id(),
                op,
                statement).observedAt(Instant.now()).build();
        RiskDecision decision = policy.evaluate(event);
        if (!decision.isAllowed()) {
            throw new IllegalArgumentException("风控拒绝：" + decision.getReason());
        }
    }

    private void assertJdbcSupported(String dbType) {
        String type = dbType != null ? dbType.toLowerCase(Locale.ROOT).trim() : "";
        switch (type) {
            case "mysql", "mariadb", "postgresql", "postgres", "h2", "sqlserver", "mssql" -> {
                // ok (sqlserver gated by driver check above)
            }
            default -> throw new IllegalArgumentException(
                    "暂不支持对该 dbType 执行管控台 SQL：" + dbType + "（可用：mysql / postgresql / sqlserver）");
        }
    }

    private TargetCreds resolveCreds(ManagedListener listener) {
        String host = listener.targetHost();
        int port = listener.targetPort();
        String database = listener.targetDatabase();
        String username = listener.targetUsername();
        String password = null;

        if (consoleStore != null && "console".equals(listener.source())) {
            Optional<ConsoleInstanceRecord> row = consoleStore.findById(listener.id());
            if (row.isPresent()) {
                ConsoleInstanceRecord r = row.get();
                host = first(r.targetHost(), host);
                port = r.targetPort() > 0 ? r.targetPort() : port;
                database = first(r.targetDatabase(), database);
                username = first(r.targetUsername(), username);
                password = r.targetPassword();
            }
        }
        if (password == null || password.isEmpty()) {
            password = gatewayConfig.getTargetPassword();
            if (!hasText(username)) {
                username = gatewayConfig.getTargetUsername();
            }
            if (!hasText(host)) {
                host = gatewayConfig.getTargetHost();
            }
            if (port <= 0) {
                port = gatewayConfig.getTargetPort();
            }
            if (!hasText(database)) {
                database = gatewayConfig.getTargetDatabase();
            }
        }
        return new TargetCreds(host, port, database, username, password);
    }

    private static Object cellValue(ResultSet rs, int index, int sqlType) throws SQLException {
        Object raw = rs.getObject(index);
        if (raw == null || rs.wasNull()) {
            return null;
        }
        if (raw instanceof Boolean || raw instanceof Number) {
            if (raw instanceof Float || raw instanceof Double) {
                return ((Number) raw).doubleValue();
            }
            if (raw instanceof Long || raw instanceof Integer || raw instanceof Short || raw instanceof Byte) {
                return ((Number) raw).longValue();
            }
            return raw;
        }
        if (sqlType == Types.BINARY || sqlType == Types.VARBINARY || sqlType == Types.LONGVARBINARY
                || sqlType == Types.BLOB) {
            return "<binary>";
        }
        String asText = String.valueOf(raw);
        if (asText.length() > CELL_MAX_CHARS) {
            return asText.substring(0, CELL_MAX_CHARS) + "…";
        }
        return asText;
    }

    private static boolean isSqlServer(String dbType) {
        String t = dbType != null ? dbType.toLowerCase(Locale.ROOT) : "";
        return "sqlserver".equals(t) || "mssql".equals(t);
    }

    private static boolean isDriverPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static int clamp(Integer value, int defaultValue, int min, int max) {
        int v = value != null ? value : defaultValue;
        return Math.max(min, Math.min(max, v));
    }

    private static String first(String preferred, String fallback) {
        return hasText(preferred) ? preferred : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record TargetCreds(String host, int port, String database, String username, String password) {
    }
}
