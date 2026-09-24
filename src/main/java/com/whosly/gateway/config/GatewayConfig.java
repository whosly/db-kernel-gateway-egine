package com.whosly.gateway.config;

import com.whosly.gateway.adapter.AbstractProtocolAdapter;
import com.whosly.gateway.adapter.MySqlProtocolAdapter;
import com.whosly.gateway.adapter.PostgreSQLProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.ClientAddressPolicy;
import com.whosly.gateway.adapter.protocol.CidrClientAddressPolicy;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficObserver;
import com.whosly.gateway.adapter.protocol.RewriteLimits;
import com.whosly.gateway.audit.AuditDestination;
import com.whosly.gateway.audit.AuditDurability;
import com.whosly.gateway.audit.AuditShipper;
import com.whosly.gateway.audit.AuditShippingOffset;
import com.whosly.gateway.audit.AuditSpool;
import com.whosly.gateway.audit.AuditSpoolConfig;
import com.whosly.gateway.audit.JdbcAuditDestination;
import com.whosly.gateway.audit.SpoolingTrafficObserver;
import com.whosly.gateway.masking.MaskingEngine;
import com.whosly.gateway.masking.MaskingRule;
import com.whosly.gateway.masking.MaskingRuleRegistry;
import com.whosly.gateway.parser.DruidSqlParser;
import com.whosly.gateway.parser.SqlParser;
import com.whosly.gateway.parser.StatementClassifier;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Gateway Config implementation.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@Configuration
public class GatewayConfig implements DisposableBean {

    @Value("${gateway.proxy-db-type:mysql}")
    private String proxyDbType;

    @Value("${gateway.proxy-port:3307}")
    private int proxyPort;

    @Value("${gateway.target.host:localhost}")
    private String targetHost;

    @Value("${gateway.target.port:13308}")
    private int targetPort;

    @Value("${gateway.target.username:}")
    private String targetUsername;

    @Value("${gateway.target.password:}")
    private String targetPassword;

    @Value("${gateway.target.database:}")
    private String targetDatabase;

    @Value("${gateway.max-connections:200}")
    private int maxConnections;

    @Value("${gateway.idle-timeout-seconds:0}")
    private long idleTimeoutSeconds;

    @Value("${gateway.allowed-client-cidrs:}")
    private String allowedClientCidrs;

    @Value("${gateway.backend-endpoints:}")
    private String backendEndpoints;

    @Value("${gateway.virtual-threads:true}")
    private boolean virtualThreads;

    @Value("${gateway.require-cleartext-inspection:#{null}}")
    private Boolean requireCleartextInspection;

    @Value("${gateway.rewrite.max-message-bytes:1048576}")
    private int rewriteMaxMessageBytes;

    @Value("${gateway.rewrite.max-hold-millis:1000}")
    private long rewriteMaxHoldMillis;

    @Value("${gateway.audit.enabled:false}")
    private boolean auditEnabled;

    @Value("${gateway.audit.spool-dir:./audit}")
    private String auditSpoolDir;

    @Value("${gateway.audit.file-name:audit.spool}")
    private String auditFileName;

    @Value("${gateway.audit.durability:strict}")
    private String auditDurability;

    @Value("${gateway.audit.batch-size:32}")
    private int auditBatchSize;

    @Value("${gateway.audit.max-batch-delay-millis:1}")
    private long auditMaxBatchDelayMillis;

    @Value("${gateway.audit.max-pending-records:4096}")
    private int auditMaxPendingRecords;

    @Value("${gateway.audit.max-enqueue-wait-millis:10}")
    private long auditMaxEnqueueWaitMillis;

    @Value("${gateway.audit.max-spool-bytes:1073741824}")
    private long auditMaxSpoolBytes;

    @Value("${gateway.audit.grade-writes:true}")
    private boolean auditGradeWrites;

    @Value("${gateway.audit.segment-bytes:67108864}")
    private long auditSegmentBytes;

    @Value("${gateway.audit.mask-statements:true}")
    private boolean auditMaskStatements;

    @Autowired(required = false)
    private MaskingRuleRegistry maskingRuleRegistry;

    @Value("${gateway.audit.destination:spool}")
    private String auditDestination;

    @Value("${gateway.audit.ship-interval-millis:1000}")
    private long auditShipIntervalMillis;

    @Value("${gateway.audit.ship-batch-size:500}")
    private int auditShipBatchSize;

    @Value("${gateway.audit.jdbc.url:}")
    private String auditJdbcUrl;

    @Value("${gateway.audit.jdbc.username:}")
    private String auditJdbcUsername;

    @Value("${gateway.audit.jdbc.password:}")
    private String auditJdbcPassword;

    @Value("${gateway.audit.jdbc.table:gateway_audit_record}")
    private String auditJdbcTable;

    private AuditSpool auditSpool;
    private AuditShipper auditShipper;

    @Bean
    public SqlParser sqlParser() {
        return new DruidSqlParser();
    }

    @Bean
    public RewriteLimits rewriteLimits() {
        return new RewriteLimits(rewriteMaxMessageBytes, rewriteMaxHoldMillis);
    }

    @Bean
    public MaskingEngine maskingEngine() {
        return new MaskingEngine(maskingRuleRegistry != null
                ? maskingRuleRegistry
                : new MaskingRuleRegistry(List.of()));
    }

    @Bean
    public DatabaseTrafficObserver databaseTrafficObserver() throws IOException {
        if (!auditEnabled) {
            return DatabaseTrafficObserver.noop();
        }
        auditSpool = new AuditSpool(auditSpoolConfig());
        startAuditShipperIfConfigured();
        StatementClassifier classifier = auditGradeWrites ? new StatementClassifier(sqlParser()) : null;
        DatabaseTrafficObserver sink = new SpoolingTrafficObserver(auditSpool, classifier);
        return auditMaskStatements ? DatabaseTrafficObserver.masking(sink) : sink;
    }

    private void startAuditShipperIfConfigured() {
        String destinationName = auditDestination == null ? "spool" : auditDestination.toLowerCase(Locale.ROOT);
        switch (destinationName) {
            case "spool" -> {
            }
            case "jdbc" -> {
                AuditDestination destination = new JdbcAuditDestination(
                        auditJdbcUrl, auditJdbcUsername, auditJdbcPassword, auditJdbcTable);
                auditShipper = new AuditShipper(auditSpool, destination, new AuditShippingOffset(auditOffsetFile()),
                        auditShipBatchSize, auditShipIntervalMillis);
                auditShipper.start();
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported gateway.audit.destination: " + auditDestination);
        }
    }

    private Path auditOffsetFile() {
        return Path.of(auditSpoolDir).resolve(auditFileName + ".offset");
    }

    private AuditSpoolConfig auditSpoolConfig() {
        AuditDurability durability = switch (auditDurability.toLowerCase(Locale.ROOT)) {
            case "strict" -> AuditDurability.STRICT;
            case "window" -> AuditDurability.WINDOW;
            default -> throw new IllegalArgumentException(
                    "Unsupported gateway.audit.durability: " + auditDurability);
        };
        return new AuditSpoolConfig(Path.of(auditSpoolDir), auditFileName, durability, auditBatchSize,
                auditMaxBatchDelayMillis, auditMaxPendingRecords, auditMaxEnqueueWaitMillis,
                auditMaxSpoolBytes, auditSegmentBytes);
    }

    @Override
    public void destroy() {
        if (auditShipper != null) {
            auditShipper.close();
        }
        if (auditSpool != null) {
            auditSpool.close();
        }
    }

    @Bean
    public ProtocolAdapter protocolAdapter() {
        switch (proxyDbType.toLowerCase()) {
            case "mysql":
                return createMySqlProtocolAdapter();
            case "postgresql":
                return createPostgreSQLProtocolAdapter();
            default:
                throw new IllegalArgumentException("Unsupported gateway proxy database protocol: " + proxyDbType);
        }
    }

    private ProtocolAdapter createMySqlProtocolAdapter() {
        MySqlProtocolAdapter adapter = new MySqlProtocolAdapter();
        adapter.setPort(proxyPort);
        adapter.setTargetHost(targetHost);
        adapter.setTargetPort(targetPort);
        adapter.setTargetUsername(targetUsername);
        adapter.setTargetPassword(targetPassword);
        adapter.setTargetDatabase(targetDatabase);
        applyConnectionGovernance(adapter);
        return adapter;
    }

    private ProtocolAdapter createPostgreSQLProtocolAdapter() {
        PostgreSQLProtocolAdapter adapter = new PostgreSQLProtocolAdapter();
        adapter.setPort(proxyPort);
        adapter.setTargetHost(targetHost);
        adapter.setTargetPort(targetPort);
        adapter.setTargetUsername(targetUsername);
        adapter.setTargetPassword(targetPassword);
        adapter.setTargetDatabase(targetDatabase);
        applyConnectionGovernance(adapter);
        return adapter;
    }

    private void applyConnectionGovernance(AbstractProtocolAdapter adapter) {
        adapter.setMaxConnections(maxConnections);
        adapter.setIdleTimeoutSeconds(idleTimeoutSeconds);
        adapter.setClientAddressPolicy(clientAddressPolicy());
        adapter.setRewriteLimits(rewriteLimits());
        adapter.setMaskingEngine(maskingEngine());
        adapter.setVirtualThreadsEnabled(virtualThreads);
        adapter.setBackendEndpoints(parseBackendEndpoints());
        adapter.setRequireCleartextInspection(resolveRequireCleartextInspection());
        try {
            adapter.setDatabaseTrafficObserver(databaseTrafficObserver());
        } catch (IOException e) {
            throw new IllegalStateException("Audit spool could not be opened", e);
        }
    }

    private boolean resolveRequireCleartextInspection() {
        if (requireCleartextInspection != null) {
            return requireCleartextInspection;
        }
        return auditEnabled;
    }

    private List<com.whosly.gateway.adapter.protocol.BackendEndpoint> parseBackendEndpoints() {
        List<com.whosly.gateway.adapter.protocol.BackendEndpoint> endpoints = new ArrayList<>();
        endpoints.add(new com.whosly.gateway.adapter.protocol.BackendEndpoint(targetHost, targetPort));
        if (backendEndpoints == null || backendEndpoints.isBlank()) {
            return endpoints;
        }
        for (String part : backendEndpoints.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            int colon = trimmed.lastIndexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                throw new IllegalArgumentException(
                        "Invalid gateway.backend-endpoints entry (expected host:port): " + trimmed);
            }
            String host = trimmed.substring(0, colon).trim();
            int port = Integer.parseInt(trimmed.substring(colon + 1).trim());
            var endpoint = new com.whosly.gateway.adapter.protocol.BackendEndpoint(host, port);
            if (!endpoints.contains(endpoint)) endpoints.add(endpoint);
        }
        return endpoints;
    }

    private ClientAddressPolicy clientAddressPolicy() {
        if (allowedClientCidrs == null || allowedClientCidrs.isBlank()) {
            return ClientAddressPolicy.allowAll();
        }
        return CidrClientAddressPolicy.of(Arrays.asList(allowedClientCidrs.split(",")));
    }

    public String getTargetHost() { return targetHost; }
    public int getTargetPort() { return targetPort; }
    public String getTargetUsername() { return targetUsername; }
    public String getTargetPassword() { return targetPassword; }
    public String getTargetDatabase() { return targetDatabase; }
    public String getProxyDbType() { return proxyDbType; }
    public int getProxyPort() { return proxyPort; }
}
