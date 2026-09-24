package com.whosly.gateway.console;

import com.whosly.gateway.adapter.AbstractProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.console.persist.ConsoleInstanceRecord;
import com.whosly.gateway.console.persist.ConsoleInstanceStore;
import com.whosly.gateway.console.schema.InstanceSchemaColumnsService;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import com.whosly.gateway.runtime.GatewayListenerRuntime.ManagedListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Protocol-agnostic backend health probe (TCP + optional JDBC).
 * Aligns with MaxScale/ProxySQL monitor style — not a full DBA health suite.
 */
@Service
public class InstanceBackendHealthService {

    private static final Logger log = LoggerFactory.getLogger(InstanceBackendHealthService.class);
    public static final int DEFAULT_TIMEOUT_MS = 3000;

    private final GatewayListenerRuntime listenerRuntime;
    private final ConsoleInstanceStore consoleStore; // nullable
    private final GatewayConfig gatewayConfig;
    private final int timeoutMs;

    public InstanceBackendHealthService(GatewayListenerRuntime listenerRuntime,
                                        Optional<ConsoleInstanceStore> consoleStore,
                                        GatewayConfig gatewayConfig) {
        this(listenerRuntime, consoleStore, gatewayConfig, DEFAULT_TIMEOUT_MS);
    }

    public InstanceBackendHealthService(GatewayListenerRuntime listenerRuntime,
                                        Optional<ConsoleInstanceStore> consoleStore,
                                        GatewayConfig gatewayConfig,
                                        int timeoutMs) {
        this.listenerRuntime = Objects.requireNonNull(listenerRuntime, "listenerRuntime");
        this.consoleStore = consoleStore.orElse(null);
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }

    public Map<String, Object> check(String instanceId) {
        ManagedListener listener = listenerRuntime.find(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown gateway instance id: " + instanceId));

        Target target = resolveTarget(listener);
        Instant checkedAt = Instant.now();
        long start = System.nanoTime();

        boolean tcpOk = false;
        String tcpMessage = null;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.host(), target.port()), timeoutMs);
            tcpOk = true;
            tcpMessage = "TCP connect ok";
        } catch (IOException e) {
            tcpMessage = "TCP connect failed: " + e.getMessage();
            log.debug("Health TCP failed for {}:{} — {}", target.host(), target.port(), e.getMessage());
        }

        Boolean jdbcOk = null;
        String jdbcMessage = null;
        if (tcpOk && hasText(target.username()) && target.password() != null) {
            try {
                String jdbcUrl = InstanceSchemaColumnsService.buildJdbcUrl(
                        listener.dbType(), target.host(), target.port(), target.database());
                Properties props = new Properties();
                props.setProperty("user", target.username());
                props.setProperty("password", target.password());
                props.setProperty("connectTimeout", String.valueOf(timeoutMs));
                props.setProperty("loginTimeout", String.valueOf(Math.max(1, timeoutMs / 1000)));
                try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
                    jdbcOk = conn.isValid(Math.max(1, timeoutMs / 1000));
                    jdbcMessage = Boolean.TRUE.equals(jdbcOk) ? "JDBC ping ok" : "JDBC isValid=false";
                }
            } catch (Exception e) {
                jdbcOk = false;
                jdbcMessage = "JDBC ping failed: " + e.getMessage();
                log.debug("Health JDBC failed for instance '{}': {}", instanceId, e.getMessage());
            }
        }

        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        boolean ok = tcpOk && (jdbcOk == null || Boolean.TRUE.equals(jdbcOk));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", ok);
        body.put("latencyMs", latencyMs);
        body.put("targetHost", target.host());
        body.put("targetPort", target.port());
        body.put("tcpOk", tcpOk);
        if (jdbcOk != null) {
            body.put("jdbcOk", jdbcOk);
        }
        String message = tcpMessage;
        if (jdbcMessage != null) {
            message = tcpMessage + "; " + jdbcMessage;
        }
        body.put("message", message);
        body.put("checkedAt", checkedAt.toString());
        body.put("instanceId", listener.id());
        return body;
    }

    private Target resolveTarget(ManagedListener listener) {
        String host = listener.targetHost();
        int port = listener.targetPort();
        String database = listener.targetDatabase();
        String username = listener.targetUsername();
        String password = null;

        ProtocolAdapter adapter = listener.adapter();
        if (adapter instanceof AbstractProtocolAdapter apa) {
            if (hasText(apa.getTargetHost())) {
                host = apa.getTargetHost();
            }
            if (apa.getTargetPort() > 0) {
                port = apa.getTargetPort();
            }
            if (hasText(apa.getTargetDatabase())) {
                database = apa.getTargetDatabase();
            }
            if (hasText(apa.getTargetUsername())) {
                username = apa.getTargetUsername();
            }
            password = apa.getTargetPassword();
        }

        if (consoleStore != null && "console".equals(listener.source())) {
            Optional<ConsoleInstanceRecord> row = consoleStore.findById(listener.id());
            if (row.isPresent()) {
                ConsoleInstanceRecord r = row.get();
                if (hasText(r.targetHost())) {
                    host = r.targetHost();
                }
                if (r.targetPort() > 0) {
                    port = r.targetPort();
                }
                if (hasText(r.targetDatabase())) {
                    database = r.targetDatabase();
                }
                if (hasText(r.targetUsername())) {
                    username = r.targetUsername();
                }
                if (r.targetPassword() != null) {
                    password = r.targetPassword();
                }
            }
        }

        if (!hasText(password)) {
            password = gatewayConfig.getTargetPassword();
        }
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
        return new Target(
                hasText(host) ? host.trim() : "127.0.0.1",
                port,
                database != null ? database : "",
                username != null ? username : "",
                password);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record Target(String host, int port, String database, String username, String password) {
    }
}
