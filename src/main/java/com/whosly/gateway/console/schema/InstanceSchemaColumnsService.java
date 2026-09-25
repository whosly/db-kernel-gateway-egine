package com.whosly.gateway.console.schema;

import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.console.persist.ConsoleInstanceRecord;
import com.whosly.gateway.console.persist.ConsoleInstanceStore;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import com.whosly.gateway.runtime.GatewayListenerRuntime.ManagedListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Protocol-agnostic JDBC metadata helper for console column hints.
 * Uses stored target credentials; never exposes passwords in responses.
 */
@Service
public class InstanceSchemaColumnsService {

    private static final Logger log = LoggerFactory.getLogger(InstanceSchemaColumnsService.class);
    private static final int MAX_COLUMNS = 2000;

    private final GatewayListenerRuntime listenerRuntime;
    private final ConsoleInstanceStore consoleStore; // nullable in tests
    private final GatewayConfig gatewayConfig;

    public InstanceSchemaColumnsService(GatewayListenerRuntime listenerRuntime,
                                        Optional<ConsoleInstanceStore> consoleStore,
                                        GatewayConfig gatewayConfig) {
        this.listenerRuntime = Objects.requireNonNull(listenerRuntime, "listenerRuntime");
        this.consoleStore = consoleStore.orElse(null);
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
    }

    public Map<String, Object> listColumns(String instanceId, String tableFilter) {
        return listColumns(instanceId, tableFilter, null);
    }

    public Map<String, Object> listColumns(String instanceId, String tableFilter, String schemaFilter) {
        ManagedListener listener = listenerRuntime.find(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown gateway instance id: " + instanceId));

        TargetCreds creds = resolveCreds(listener);
        if (!hasText(creds.password()) && !hasText(creds.username())) {
            throw new SchemaConnectException("实例未配置目标凭据，无法拉取列元数据");
        }
        String jdbcUrl = buildJdbcUrl(listener.dbType(), creds.host(), creds.port(), creds.database());
        List<Map<String, Object>> columns = new ArrayList<>();
        Properties props = connectProps(creds);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
            DatabaseMetaData meta = conn.getMetaData();
            String catalog = conn.getCatalog();
            String schema = hasText(schemaFilter) ? schemaFilter.trim() : null;
            String tablePattern = hasText(tableFilter) ? tableFilter.trim() : "%";
            try (ResultSet rs = meta.getColumns(catalog, schema, tablePattern, "%")) {
                while (rs.next() && columns.size() < MAX_COLUMNS) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("name", rs.getString("COLUMN_NAME"));
                    col.put("table", rs.getString("TABLE_NAME"));
                    col.put("schema", rs.getString("TABLE_SCHEM"));
                    col.put("nullable", rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                    col.put("typeName", rs.getString("TYPE_NAME"));
                    columns.add(col);
                }
            }
        } catch (SQLException e) {
            log.warn("Schema columns fetch failed for instance '{}': {}", instanceId, e.getMessage());
            throw new SchemaConnectException("连接目标库拉取列元数据失败：" + e.getMessage(), e);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instanceId", listener.id());
        body.put("dbType", listener.dbType());
        body.put("table", hasText(tableFilter) ? tableFilter.trim() : null);
        body.put("schema", hasText(schemaFilter) ? schemaFilter.trim() : null);
        body.put("columns", columns);
        body.put("count", columns.size());
        return body;
    }

    /**
     * Protocol-agnostic schema/table catalog via JDBC metadata (direct target).
     * Tree is metadata-only; SQL execute stays via proxy listenPort.
     */
    public Map<String, Object> listCatalog(String instanceId) {
        ManagedListener listener = listenerRuntime.find(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown gateway instance id: " + instanceId));

        TargetCreds creds = resolveCreds(listener);
        if (!hasText(creds.password()) && !hasText(creds.username())) {
            throw new SchemaConnectException("实例未配置目标凭据，无法拉取 schema catalog");
        }
        String jdbcUrl = buildJdbcUrl(listener.dbType(), creds.host(), creds.port(), creds.database());
        Properties props = connectProps(creds);

        List<Map<String, Object>> schemas = new ArrayList<>();
        List<Map<String, Object>> tables = new ArrayList<>();
        final int maxSchemas = 500;
        final int maxTables = 5000;

        try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
            DatabaseMetaData meta = conn.getMetaData();
            String catalog = conn.getCatalog();
            java.util.LinkedHashSet<String> schemaNames = new java.util.LinkedHashSet<>();

            try (ResultSet rs = meta.getSchemas()) {
                while (rs.next() && schemaNames.size() < maxSchemas) {
                    String name = rs.getString("TABLE_SCHEM");
                    if (hasText(name)) {
                        schemaNames.add(name);
                    }
                }
            } catch (SQLException e) {
                log.debug("getSchemas unavailable for {}: {}", listener.dbType(), e.getMessage());
            }

            // MySQL often exposes catalogs as "schemas" for tooling; fall back to catalog name
            if (schemaNames.isEmpty() && hasText(catalog)) {
                schemaNames.add(catalog);
            }
            if (schemaNames.isEmpty()) {
                schemaNames.add("");
            }

            for (String s : schemaNames) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", s == null || s.isEmpty() ? "(default)" : s);
                schemas.add(row);
            }

            String[] types = new String[]{"TABLE", "VIEW", "BASE TABLE"};
            try (ResultSet rs = meta.getTables(catalog, null, "%", types)) {
                while (rs.next() && tables.size() < maxTables) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    String schem = rs.getString("TABLE_SCHEM");
                    if (!hasText(schem)) {
                        schem = rs.getString("TABLE_CAT");
                    }
                    t.put("schema", hasText(schem) ? schem : "");
                    t.put("name", rs.getString("TABLE_NAME"));
                    t.put("type", rs.getString("TABLE_TYPE"));
                    tables.add(t);
                }
            }
        } catch (SQLException e) {
            log.warn("Schema catalog fetch failed for instance '{}': {}", instanceId, e.getMessage());
            throw new SchemaConnectException("连接目标库拉取 schema catalog 失败：" + e.getMessage(), e);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instanceId", listener.id());
        body.put("dbType", listener.dbType());
        body.put("schemas", schemas);
        body.put("tables", tables);
        body.put("schemaCount", schemas.size());
        body.put("tableCount", tables.size());
        body.put("note", "直连目标 JDBC 元数据；SQL 执行仍经代理 listenPort");
        return body;
    }

    private static Properties connectProps(TargetCreds creds) {
        Properties props = new Properties();
        if (hasText(creds.username())) {
            props.setProperty("user", creds.username());
        }
        if (creds.password() != null) {
            props.setProperty("password", creds.password());
        }
        props.setProperty("connectTimeout", "5000");
        props.setProperty("loginTimeout", "5");
        return props;
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
            // YAML/config instances: fall back to process target password
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

    /**
     * Build a JDBC URL from dbType — protocol-agnostic mapping, no brand UI forks.
     */
    public static String buildJdbcUrl(String dbType, String host, int port, String database) {
        String type = dbType != null ? dbType.toLowerCase(Locale.ROOT).trim() : "";
        String h = hasText(host) ? host.trim() : "127.0.0.1";
        String db = database != null ? database.trim() : "";
        return switch (type) {
            case "mysql", "mariadb" -> "jdbc:mysql://" + h + ":" + port + "/" + db
                    + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000";
            case "postgresql", "postgres" -> "jdbc:postgresql://" + h + ":" + port + "/" + db;
            case "sqlserver", "mssql" -> "jdbc:sqlserver://" + h + ":" + port
                    + (hasText(db) ? ";databaseName=" + db : "")
                    + ";encrypt=false;loginTimeout=5";
            case "h2" -> hasText(db) && db.startsWith("jdbc:")
                    ? db
                    : "jdbc:h2:mem:schema-hint;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
            default -> throw new SchemaConnectException(
                    "暂不支持对该 dbType 做 JDBC 列提示：" + dbType + "（可用：mysql/postgresql/sqlserver/h2）");
        };
    }

    private static String first(String preferred, String fallback) {
        return hasText(preferred) ? preferred : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record TargetCreds(String host, int port, String database, String username, String password) {
    }

    /** Mapped to HTTP 502 by console controller. */
    public static final class SchemaConnectException extends RuntimeException {
        public SchemaConnectException(String message) {
            super(message);
        }

        public SchemaConnectException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
