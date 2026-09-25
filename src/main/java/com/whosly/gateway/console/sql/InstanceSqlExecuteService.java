package com.whosly.gateway.console.sql;

import com.whosly.gateway.adapter.protocol.DatabaseRiskPolicy;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.MutableDatabaseRiskPolicy;
import com.whosly.gateway.adapter.protocol.RiskDecision;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.runtime.spi.PersistedInstance;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Console SQL workspace: for wire-protocol types (MySQL / PostgreSQL / MariaDB /
 * SQL Server), JDBC connects to the instance <em>proxy listen port</em> so masking,
 * traffic observation, and data-plane risk apply. Schema-columns discovery remains
 * direct target JDBC.
 *
 * <p>Exception: {@code h2} lab/unit tests keep direct JDBC (no ProtocolAdapter wire
 * path in product).</p>
 *
 * <p>Supports multi-statement scripts (semicolon-separated, max
 * {@link SqlStatementSplitter#DEFAULT_MAX_STATEMENTS}) executed sequentially, and
 * best-effort cancel via {@link Statement#cancel()} + close (driver/proxy dependent;
 * not TDS Attention / PG cancel key).</p>
 */
@Service
public class InstanceSqlExecuteService {

    private static final Logger log = LoggerFactory.getLogger(InstanceSqlExecuteService.class);
    private static final int DEFAULT_MAX_ROWS = 200;
    private static final int CAP_MAX_ROWS = 1000;
    private static final int DEFAULT_TIMEOUT_MS = 15_000;
    private static final int CAP_TIMEOUT_MS = 60_000;
    private static final int CELL_MAX_CHARS = 4096;

    private final GatewayListenerRuntime listenerRuntime;
    private final ConsoleInstanceStore consoleStore; // nullable
    private final GatewayConfig gatewayConfig;
    private final MutableDatabaseRiskPolicy riskPolicy; // nullable
    private final ConcurrentHashMap<String, RunningExecution> runningById = new ConcurrentHashMap<>();

    public InstanceSqlExecuteService(GatewayListenerRuntime listenerRuntime,
                                     Optional<ConsoleInstanceStore> consoleStore,
                                     GatewayConfig gatewayConfig,
                                     Optional<MutableDatabaseRiskPolicy> riskPolicy) {
        this.listenerRuntime = Objects.requireNonNull(listenerRuntime, "listenerRuntime");
        this.consoleStore = consoleStore.orElse(null);
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        this.riskPolicy = riskPolicy.orElse(null);
    }

    /** Backward-compatible entry: no client executionId, stop on first error. */
    public Map<String, Object> execute(String instanceId, String sql, Integer maxRows, Integer timeoutMs) {
        return execute(instanceId, sql, maxRows, timeoutMs, null, false);
    }

    /**
     * @param executionId     optional client-supplied id for cancel; generated if blank
     * @param continueOnError when true, keep going after a statement error; default stop
     */
    public Map<String, Object> execute(String instanceId, String sql, Integer maxRows, Integer timeoutMs,
                                       String executionId, boolean continueOnError) {
        ManagedListener listener = listenerRuntime.find(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown gateway instance id: " + instanceId));

        String script = sql == null ? "" : sql.trim();
        if (script.isEmpty()) {
            throw new IllegalArgumentException("sql is required");
        }

        List<String> statements = SqlStatementSplitter.split(script);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("sql is required");
        }

        int rowsCap = clamp(maxRows, DEFAULT_MAX_ROWS, 1, CAP_MAX_ROWS);
        int timeout = clamp(timeoutMs, DEFAULT_TIMEOUT_MS, 1000, CAP_TIMEOUT_MS);
        int queryTimeoutSec = Math.max(1, (int) Math.ceil(timeout / 1000.0));

        // Risk: evaluate every statement up front (fail fast with clear index)
        for (int i = 0; i < statements.size(); i++) {
            try {
                applyRiskPolicy(listener, statements.get(i));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "语句[" + i + "] 风控拒绝：" + stripRiskPrefix(ex.getMessage()), ex);
            }
        }

        TargetCreds creds = resolveCreds(listener);
        String typeForCred = listener.dbType() != null ? listener.dbType().toLowerCase(Locale.ROOT) : "";
        if (!"h2".equals(typeForCred) && !hasText(creds.password())) {
            throw new IllegalArgumentException("实例未配置目标密码，请先编辑实例写入凭据后再执行 SQL");
        }

        String dbType = listener.dbType();
        assertJdbcSupported(dbType);

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

        String execId = hasText(executionId) ? executionId.trim() : UUID.randomUUID().toString();
        RunningExecution running = new RunningExecution(execId, listener.id());
        RunningExecution prev = runningById.putIfAbsent(execId, running);
        if (prev != null) {
            throw new IllegalArgumentException("executionId 已在执行中：" + execId);
        }

        long started = System.nanoTime();
        List<Map<String, Object>> results = new ArrayList<>();
        Integer stoppedAt = null;
        boolean overallOk = true;
        String cancelNote = null;

        try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
            running.connection.set(conn);
            if (running.cancelled.get()) {
                throw new SchemaConnectException("执行已取消");
            }

            long deadlineNanos = started + timeout * 1_000_000L;

            for (int i = 0; i < statements.size(); i++) {
                if (running.cancelled.get()) {
                    overallOk = false;
                    stoppedAt = i;
                    cancelNote = "已在语句[" + i + "] 前取消";
                    break;
                }
                long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    overallOk = false;
                    stoppedAt = i;
                    Map<String, Object> timeoutResult = new LinkedHashMap<>();
                    timeoutResult.put("index", i);
                    timeoutResult.put("ok", false);
                    timeoutResult.put("sql", statements.get(i));
                    timeoutResult.put("error", "整体超时（timeoutMs=" + timeout + "）");
                    results.add(timeoutResult);
                    break;
                }
                int stmtTimeoutSec = Math.max(1, (int) Math.ceil(Math.min(remainingMs, timeout) / 1000.0));

                String statement = statements.get(i);
                long stmtStarted = System.nanoTime();
                Map<String, Object> one;
                try (Statement stmt = conn.createStatement()) {
                    running.statement.set(stmt);
                    stmt.setMaxRows(rowsCap + 1);
                    stmt.setQueryTimeout(stmtTimeoutSec);
                    one = runOneStatement(stmt, statement, rowsCap, i);
                    one.put("durationMs", (System.nanoTime() - stmtStarted) / 1_000_000L);
                    results.add(one);
                } catch (SQLException e) {
                    log.warn("SQL execute failed for instance '{}' stmt[{}]: {}", instanceId, i, e.getMessage());
                    overallOk = false;
                    stoppedAt = i;
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("index", i);
                    err.put("ok", false);
                    err.put("sql", statement);
                    err.put("error", e.getMessage());
                    err.put("durationMs", (System.nanoTime() - stmtStarted) / 1_000_000L);
                    results.add(err);
                    if (!continueOnError) {
                        break;
                    }
                } finally {
                    running.statement.set(null);
                }

                if (running.cancelled.get() && Boolean.TRUE.equals(results.get(results.size() - 1).get("ok"))) {
                    // cancel arrived after a successful statement; stop before next
                    overallOk = false;
                    if (stoppedAt == null) {
                        stoppedAt = i + 1;
                    }
                    cancelNote = "已在语句[" + i + "] 之后取消";
                    break;
                }
            }
        } catch (SQLException e) {
            log.warn("SQL execute failed for instance '{}': {}", instanceId, e.getMessage());
            runningById.remove(execId, running);
            if (running.cancelled.get()) {
                throw new SchemaConnectException("执行已取消：" + e.getMessage(), e);
            }
            throw new SchemaConnectException("连接或执行失败：" + e.getMessage(), e);
        } finally {
            running.statement.set(null);
            running.connection.set(null);
            runningById.remove(execId, running);
        }

        if (running.cancelled.get() && results.isEmpty()) {
            throw new SchemaConnectException("执行已取消");
        }

        long durationMs = (System.nanoTime() - started) / 1_000_000L;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", overallOk && !running.cancelled.get());
        body.put("executionId", execId);
        body.put("statementCount", statements.size());
        body.put("results", results);
        if (stoppedAt != null) {
            body.put("stoppedAt", stoppedAt);
        }
        if (cancelNote != null || running.cancelled.get()) {
            body.put("cancelled", true);
            if (cancelNote != null) {
                body.put("cancelNote", cancelNote);
            }
        }
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
        body.put("cancelBestEffort", true);
        body.put("cancelDisclaimer",
                "取消为尽力而为（JDBC Statement.cancel + close），依赖驱动/代理；不等同于 TDS Attention / PG CancelRequest");

        // Backward-compatible flatten of first / only successful result-set shape
        flattenPrimaryResult(body, results);

        if (Boolean.TRUE.equals(body.get("cancelled"))) {
            body.put("message", cancelNote != null ? cancelNote : "执行已取消");
        } else if (!overallOk) {
            body.put("message", stoppedAt != null
                    ? "在语句[" + stoppedAt + "] 失败后停止"
                    : "执行失败");
        } else if (statements.size() > 1) {
            body.put("message", "全部 " + statements.size() + " 条语句执行成功");
        } else if (Boolean.TRUE.equals(body.get("truncated"))) {
            body.put("message", "结果已截断至 maxRows=" + rowsCap);
        } else {
            body.put("message", "执行成功");
        }
        return body;
    }

    /**
     * Best-effort cancel of an in-flight console SQL execution.
     * Calls {@link Statement#cancel()} and closes the statement/connection.
     */
    public Map<String, Object> cancel(String executionId) {
        if (!hasText(executionId)) {
            throw new IllegalArgumentException("executionId is required");
        }
        String id = executionId.trim();
        RunningExecution running = runningById.get(id);
        if (running == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("executionId", id);
            body.put("found", false);
            body.put("message", "未找到进行中的执行（可能已结束）");
            body.put("cancelBestEffort", true);
            return body;
        }
        boolean cancelled = running.requestCancel();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("executionId", id);
        body.put("instanceId", running.instanceId);
        body.put("found", true);
        body.put("cancelRequested", cancelled);
        body.put("message", "已请求取消（尽力而为；驱动/代理可能延迟或忽略）");
        body.put("cancelBestEffort", true);
        body.put("cancelDisclaimer",
                "取消为尽力而为（JDBC Statement.cancel + close），依赖驱动/代理；不等同于 TDS Attention / PG CancelRequest");
        return body;
    }

    /**
     * Cancel by executionId, optionally verifying it belongs to {@code instanceId}.
     */
    public Map<String, Object> cancel(String instanceId, String executionId) {
        if (!hasText(executionId)) {
            throw new IllegalArgumentException("executionId is required");
        }
        RunningExecution running = runningById.get(executionId.trim());
        if (running != null && hasText(instanceId) && !instanceId.equals(running.instanceId)) {
            throw new IllegalArgumentException("executionId 不属于该实例");
        }
        return cancel(executionId);
    }

    /** Package-visible for tests. */
    int runningCount() {
        return runningById.size();
    }

    private static void flattenPrimaryResult(Map<String, Object> body, List<Map<String, Object>> results) {
        Map<String, Object> primary = null;
        for (Map<String, Object> r : results) {
            if (Boolean.TRUE.equals(r.get("ok"))) {
                primary = r;
                break;
            }
        }
        if (primary == null && !results.isEmpty()) {
            primary = results.get(0);
        }
        if (primary == null) {
            body.put("columns", List.of());
            body.put("rows", List.of());
            body.put("rowCount", 0);
            body.put("truncated", false);
            return;
        }
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) primary.getOrDefault("columns", List.of());
        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) primary.getOrDefault("rows", List.of());
        body.put("columns", columns);
        body.put("rows", rows);
        body.put("rowCount", primary.getOrDefault("rowCount", rows.size()));
        body.put("truncated", primary.getOrDefault("truncated", false));
        if (primary.containsKey("updateCount")) {
            body.put("updateCount", primary.get("updateCount"));
        }
        if (primary.containsKey("warnings")) {
            body.put("warnings", primary.get("warnings"));
        }
    }

    private Map<String, Object> runOneStatement(Statement stmt, String statement, int rowsCap, int index)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        int updateCount = -1;
        List<String> warnings = new ArrayList<>();

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

        Map<String, Object> one = new LinkedHashMap<>();
        one.put("index", index);
        one.put("ok", true);
        one.put("sql", statement);
        one.put("columns", columns);
        one.put("rows", rows);
        one.put("rowCount", rows.size());
        one.put("truncated", truncated);
        if (updateCount >= 0) {
            one.put("updateCount", updateCount);
        }
        if (!warnings.isEmpty()) {
            one.put("warnings", warnings);
        }
        return one;
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

    /**
     * @deprecated Prefer {@link SqlStatementSplitter#split(String)}; kept for older tests.
     *             Single-statement-only validation is no longer enforced on execute.
     */
    @Deprecated
    public static void validateSingleStatement(String sql) {
        List<String> parts = SqlStatementSplitter.split(sql, SqlStatementSplitter.DEFAULT_MAX_STATEMENTS);
        if (parts.size() > 1) {
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

    private static String stripRiskPrefix(String message) {
        if (message == null) {
            return "";
        }
        String prefix = "风控拒绝：";
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
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
            Optional<PersistedInstance> row = consoleStore.findById(listener.id());
            if (row.isPresent()) {
                PersistedInstance r = row.get();
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

    static final class RunningExecution {
        final String executionId;
        final String instanceId;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicReference<Statement> statement = new AtomicReference<>();
        final AtomicReference<Connection> connection = new AtomicReference<>();

        RunningExecution(String executionId, String instanceId) {
            this.executionId = executionId;
            this.instanceId = instanceId;
        }

        boolean requestCancel() {
            cancelled.set(true);
            Statement stmt = statement.get();
            if (stmt != null) {
                try {
                    stmt.cancel();
                } catch (SQLException e) {
                    log.debug("Statement.cancel failed for {}: {}", executionId, e.getMessage());
                }
                try {
                    stmt.close();
                } catch (SQLException e) {
                    log.debug("Statement.close after cancel failed for {}: {}", executionId, e.getMessage());
                }
            }
            Connection conn = connection.get();
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.debug("Connection.close after cancel failed for {}: {}", executionId, e.getMessage());
                }
            }
            return true;
        }
    }
}
