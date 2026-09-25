package com.whosly.gateway.config;

import com.whosly.gateway.adapter.AbstractProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapterRegistry;
import com.whosly.gateway.adapter.protocol.BackendSessionReset;
import com.whosly.gateway.adapter.protocol.ClientAddressPolicy;
import com.whosly.gateway.adapter.protocol.CidrClientAddressPolicy;
import com.whosly.gateway.adapter.protocol.DatabaseRiskPolicy;
import com.whosly.gateway.adapter.protocol.DenyListDatabaseRiskPolicy;
import com.whosly.gateway.adapter.protocol.MutableDatabaseRiskPolicy;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.adapter.protocol.ClientTlsTerminator;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficObserver;
import com.whosly.gateway.adapter.protocol.RewriteLimits;
import com.whosly.gateway.adapter.protocol.BackendEndpointParser;
import com.whosly.gateway.adapter.protocol.RoutingRule;
import com.whosly.gateway.adapter.protocol.WeightedEndpoint;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.whosly.gateway.audit.AuditDestination;
import com.whosly.gateway.audit.AuditDurability;
import com.whosly.gateway.audit.AuditShipper;
import com.whosly.gateway.audit.AuditShippingOffset;
import com.whosly.gateway.audit.AuditSpool;
import com.whosly.gateway.audit.AuditSpoolConfig;
import com.whosly.gateway.audit.JdbcAuditDestination;
import com.whosly.gateway.audit.SpoolingTrafficObserver;
import com.whosly.gateway.masking.MaskingEngine;
import com.whosly.gateway.masking.MaskingCipher;
import com.whosly.gateway.masking.MaskingKeyProvider;
import com.whosly.gateway.masking.MaskingRuleRegistry;
import com.whosly.gateway.parser.DruidSqlParser;
import com.whosly.gateway.parser.SqlParser;
import com.whosly.gateway.parser.StatementClassifier;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import com.whosly.gateway.console.SupportedDatabaseCatalog;
import com.whosly.gateway.console.persist.ConsoleInstanceStore;
import com.whosly.gateway.console.masking.InstanceMaskingEngineFactory;
import com.whosly.gateway.console.observe.RecentTrafficRing;
import com.whosly.gateway.console.masking.InstanceMaskingRuleCompiler;
import com.whosly.gateway.console.security.ConsoleMaskingKeyHolder;
import com.whosly.gateway.console.security.ConsoleSecretCipher;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.nio.file.Path;
import java.nio.file.Files;
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
@EnableConfigurationProperties({GatewayRoutingProperties.class, GatewayCatalogProperties.class, GatewayInstanceProperties.class})
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

    /**
     * Comma-separated protocol operation names to deny (case-insensitive),
     * e.g. {@code COM_PROCESS_KILL,DROP}. Empty keeps allow-all.
     */
    @Value("${gateway.risk.denied-operations:}")
    private String riskDeniedOperations;

    /**
     * Comma-separated statement keyword substrings to deny (case-insensitive),
     * e.g. {@code drop table,truncate}. Empty keeps allow-all when operations
     * are also empty.
     */
    @Value("${gateway.risk.denied-statement-keywords:}")
    private String riskDeniedStatementKeywords;

    @Value("${gateway.backend-endpoints:}")
    private String backendEndpoints;

    @Value("${gateway.virtual-threads:true}")
    private boolean virtualThreads;

    @Value("${gateway.require-cleartext-inspection:#{null}}")
    private Boolean requireCleartextInspection;

    /** Protocol-agnostic backend pool (default off — backward-safe). */
    @Value("${gateway.pool.enabled:false}")
    private boolean poolEnabled;

    @Value("${gateway.pool.max-idle:8}")
    private int poolMaxIdle;

    /**
     * Pool session reset strategy (protocol-agnostic config; implementations are SPI).
     * {@code none} (default): close-if-unsafe only — backward safe.
     * {@code protocol}: use the registered {@link BackendSessionReset} for the
     * current {@code gateway.proxy-db-type} (MySQL COM_RESET_CONNECTION,
     * PostgreSQL DISCARD ALL; other DBs may register their own).
     * Only meaningful when {@code gateway.pool.enabled=true}.
     */
    @Value("${gateway.pool.reset-mode:none}")
    private String poolResetMode;

    /**
     * Stunnel-style client TLS terminate. Off unless enabled <em>and</em> a keystore path is set.
     * Shared by every proxy-db-type (mysql / postgresql / future oracle / sqlserver).
     */
    @Value("${gateway.tls.enabled:false}")
    private boolean tlsEnabled;

    @Value("${gateway.tls.keystore-path:}")
    private String tlsKeystorePath;

    @Value("${gateway.tls.keystore-password:}")
    private String tlsKeystorePassword;

    /** PKCS12 or JKS; blank uses {@code KeyStore.getDefaultType()}. */
    @Value("${gateway.tls.keystore-type:}")
    private String tlsKeystoreType;

    /** Optional; unused by the default KeyManager init (whole-store password). Reserved for docs. */
    @Value("${gateway.tls.key-alias:}")
    private String tlsKeyAlias;

    @Autowired(required = false)
    private GatewayRoutingProperties routingProperties;

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

    /** Base64 AES key for EncryptingRule (console encrypt strategy). Empty = encrypt rejected. */
    @Value("${gateway.masking.key-base64:}")
    private String maskingKeyBase64;

    @Value("${gateway.masking.key-id:default}")
    private String maskingKeyId;

    /** 32-byte AES master key (Base64) for control-plane password / secrets encryption. */
    @Value("${gateway.console.secret-key-base64:}")
    private String consoleSecretKeyBase64;

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

    /**
     * Shared hot-swappable risk policy. YAML seeds the initial deny lists;
     * console {@code PUT /risk-policy} replaces the delegate without restart.
     */
    @Bean
    public MutableDatabaseRiskPolicy mutableDatabaseRiskPolicy() {
        MutableDatabaseRiskPolicy mutable = new MutableDatabaseRiskPolicy();
        mutable.replace(DenyListDatabaseRiskPolicy.of(
                splitCsv(riskDeniedOperations),
                splitCsv(riskDeniedStatementKeywords)));
        return mutable;
    }

    @Bean
    public ConsoleSecretCipher consoleSecretCipher() {
        return ConsoleSecretCipher.fromBase64MasterKey(consoleSecretKeyBase64);
    }

    /**
     * Mutable masking-key holder: yaml config at boot, overridable via console API.
     */
    @Bean
    public ConsoleMaskingKeyHolder consoleMaskingKeyHolder() {
        String id = maskingKeyId != null && !maskingKeyId.isBlank() ? maskingKeyId : "default";
        MaskingCipher cipher = null;
        if (maskingKeyBase64 != null && !maskingKeyBase64.isBlank()) {
            cipher = new MaskingCipher(MaskingKeyProvider.ofBase64(id, maskingKeyBase64.trim()));
        }
        return new ConsoleMaskingKeyHolder(cipher, id);
    }

    /**
     * Compiles H2 console masking rules. Encrypt strategy uses {@link ConsoleMaskingKeyHolder}
     * (yaml key and/or console-stored override).
     */
    @Bean
    public InstanceMaskingRuleCompiler instanceMaskingRuleCompiler(ConsoleMaskingKeyHolder holder) {
        return new InstanceMaskingRuleCompiler(holder);
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
    public ProtocolAdapterRegistry protocolAdapterRegistry() {
        // Built-ins: mysql + postgresql; reserved stubs: oracle / sqlserver / mssql.
        // Callers may obtain this bean and register("mydb", MyDbAdapter::new, MyDbReset::new).
        return ProtocolAdapterRegistry.withBuiltIns();
    }

    /**
     * Non-Spring / unit-test entry: one adapter from process-level {@code proxy-*} / {@code target.*}.
     */
    public ProtocolAdapter protocolAdapter() {
        return buildAdapter(
                protocolAdapterRegistry(),
                proxyDbType,
                proxyPort,
                targetHost,
                targetPort,
                targetUsername,
                targetPassword,
                targetDatabase,
                new GatewayRuntimeMetrics());
    }

    /**
     * Same-JVM multi-listener manager. Builds 1..N adapters from {@code gateway.instances}
     * (or a synthetic default when the list is empty).
     */
    @Bean
    public GatewayListenerRuntime gatewayListenerRuntime(
            ProtocolAdapterRegistry protocolAdapterRegistry,
            GatewayInstanceProperties instanceProperties,
            SupportedDatabaseCatalog catalog,
            ConsoleInstanceStore consoleInstanceStore,
            @Autowired(required = false) InstanceMaskingEngineFactory maskingEngineFactory,
            @Autowired(required = false) RecentTrafficRing recentTrafficRing) {
        return new GatewayListenerRuntime(
                this, protocolAdapterRegistry, instanceProperties, catalog,
                consoleInstanceStore, maskingEngineFactory, recentTrafficRing);
    }

    /**
     * Legacy single-adapter bean for {@code /gateway/*}, CLI, and Actuator.
     * Aliases the runtime "legacy" instance (proxy-* match, else {@code default}, else first bound).
     */
    @Bean
    @Primary
    public ProtocolAdapter protocolAdapter(GatewayListenerRuntime gatewayListenerRuntime) {
        return gatewayListenerRuntime.getLegacyAdapter();
    }

    /**
     * Process-visible metrics bean aliases the legacy instance counters
     * ({@code /gateway/metrics}). Per-instance maps live on each listener — see console registry.
     */
    @Bean
    public GatewayRuntimeMetrics gatewayRuntimeMetrics(GatewayListenerRuntime gatewayListenerRuntime) {
        return gatewayListenerRuntime.getLegacyMetrics();
    }

    /**
     * Builds one governed adapter for a concrete instance (or the process-level default).
     * Shared pool/TLS/routing/audit/risk beans are applied; metrics are per-call (per-instance).
     */
    public ProtocolAdapter buildAdapter(ProtocolAdapterRegistry protocolAdapterRegistry,
                                        String dbType,
                                        int listenPort,
                                        String instanceTargetHost,
                                        int instanceTargetPort,
                                        String instanceTargetUsername,
                                        String instanceTargetPassword,
                                        String instanceTargetDatabase,
                                        GatewayRuntimeMetrics metrics) {
        ProtocolAdapter created;
        try {
            created = protocolAdapterRegistry.create(dbType);
        } catch (UnsupportedOperationException e) {
            // Reserved stubs throw UOE — surface as IllegalArgumentException for config errors.
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        if (!(created instanceof AbstractProtocolAdapter adapter)) {
            throw new IllegalStateException(
                    "ProtocolAdapter for '" + dbType + "' must extend AbstractProtocolAdapter "
                            + "so pool/TLS/governance can be applied");
        }
        adapter.setPort(listenPort);
        adapter.setTargetHost(instanceTargetHost);
        adapter.setTargetPort(instanceTargetPort);
        adapter.setTargetUsername(instanceTargetUsername);
        adapter.setTargetPassword(instanceTargetPassword);
        adapter.setTargetDatabase(instanceTargetDatabase);
        applyConnectionGovernance(adapter, protocolAdapterRegistry, dbType,
                instanceTargetHost, instanceTargetPort, metrics);
        return adapter;
    }

    private void applyConnectionGovernance(AbstractProtocolAdapter adapter,
                                           ProtocolAdapterRegistry protocolAdapterRegistry,
                                           String dbType,
                                           String instanceTargetHost,
                                           int instanceTargetPort,
                                           GatewayRuntimeMetrics metrics) {
        adapter.setMaxConnections(maxConnections);
        adapter.setIdleTimeoutSeconds(idleTimeoutSeconds);
        adapter.setClientAddressPolicy(clientAddressPolicy());
        adapter.setDatabaseRiskPolicy(mutableDatabaseRiskPolicy());
        adapter.setRewriteLimits(rewriteLimits());
        adapter.setMaskingEngine(maskingEngine());
        adapter.setVirtualThreadsEnabled(virtualThreads);
        adapter.setBackendEndpoints(parseBackendEndpoints(instanceTargetHost, instanceTargetPort));
        adapter.setRequireCleartextInspection(resolveRequireCleartextInspection());
        adapter.setPoolEnabled(poolEnabled);
        adapter.setPoolMaxIdle(poolMaxIdle);
        adapter.setBackendSessionReset(resolveBackendSessionReset(protocolAdapterRegistry, dbType));
        adapter.setClientTlsTerminator(buildClientTlsTerminator());
        adapter.setRuntimeMetrics(metrics != null ? metrics : new GatewayRuntimeMetrics());
        applyRouting(adapter);
        try {
            adapter.setDatabaseTrafficObserver(databaseTrafficObserver());
        } catch (IOException e) {
            throw new IllegalStateException("Audit spool could not be opened", e);
        }
    }

    /**
     * Resolves {@code gateway.pool.reset-mode}.
     * {@code none} (default): {@link BackendSessionReset#none()}.
     * {@code protocol}: SPI from the registry for the instance db-type.
     */
    private BackendSessionReset resolveBackendSessionReset(ProtocolAdapterRegistry registry, String dbType) {
        String mode = poolResetMode == null ? "none" : poolResetMode.toLowerCase(Locale.ROOT).trim();
        return switch (mode) {
            case "none" -> BackendSessionReset.none();
            case "protocol" -> registry.createSessionReset(dbType);
            default -> throw new IllegalArgumentException(
                    "Unsupported gateway.pool.reset-mode: " + poolResetMode
                            + " (supported: none, protocol)");
        };
    }


    /**
     * Shared client-leg TLS terminator for every {@code gateway.proxy-db-type}.
     * Disabled when {@code gateway.tls.enabled=false}; requires a keystore path when enabled.
     */
    private ClientTlsTerminator buildClientTlsTerminator() {
        if (!tlsEnabled) {
            return ClientTlsTerminator.disabled();
        }
        if (tlsKeystorePath == null || tlsKeystorePath.isBlank()) {
            throw new IllegalStateException(
                    "gateway.tls.enabled=true requires gateway.tls.keystore-path "
                            + "(TLS terminate stays off without certificates)");
        }
        Path path = Path.of(tlsKeystorePath);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("gateway.tls.keystore-path is not a readable file: " + path);
        }
        char[] password = tlsKeystorePassword != null ? tlsKeystorePassword.toCharArray() : new char[0];
        try {
            return ClientTlsTerminator.fromKeyStore(path, password, tlsKeystoreType);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Failed to load gateway TLS keystore: " + path, e);
        }
    }

    private boolean resolveRequireCleartextInspection() {
        if (requireCleartextInspection != null) {
            return requireCleartextInspection;
        }
        return auditEnabled;
    }

    private void applyRouting(AbstractProtocolAdapter adapter) {
        GatewayRoutingProperties props = routingProperties != null
                ? routingProperties : new GatewayRoutingProperties();
        adapter.setRoutingEnabled(props.isEnabled());
        if (!props.isEnabled()) {
            adapter.setRoutingRules(List.of());
            return;
        }
        List<RoutingRule> rules = new ArrayList<>();
        for (GatewayRoutingProperties.Rule rule : props.getRules()) {
            if (rule == null) {
                continue;
            }
            List<WeightedEndpoint> endpoints = BackendEndpointParser.parseWeightedEndpoints(rule.getEndpoints());
            if (endpoints.isEmpty()) {
                throw new IllegalArgumentException(
                        "gateway.routing rule requires endpoints (host:port[,host:port:weight…])");
            }
            rules.add(new RoutingRule(rule.getMatchDatabase(), rule.getMatchUsername(), endpoints));
        }
        adapter.setRoutingRules(rules);
    }

    private List<com.whosly.gateway.adapter.protocol.BackendEndpoint> parseBackendEndpoints(
            String primaryHost, int primaryPort) {
        List<com.whosly.gateway.adapter.protocol.BackendEndpoint> endpoints = new ArrayList<>();
        endpoints.add(new com.whosly.gateway.adapter.protocol.BackendEndpoint(primaryHost, primaryPort));
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

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.asList(csv.split(","));
    }

    public String getTargetHost() { return targetHost; }
    public int getTargetPort() { return targetPort; }
    public String getTargetUsername() { return targetUsername; }
    public String getTargetPassword() { return targetPassword; }
    public String getTargetDatabase() { return targetDatabase; }
    public boolean isAuditEnabled() { return auditEnabled; }
    public boolean isAuditMaskStatements() { return auditMaskStatements; }
    public String getAuditDestination() { return auditDestination; }
    public boolean isConsoleSecretKeyConfigured() {
        return consoleSecretKeyBase64 != null && !consoleSecretKeyBase64.isBlank();
    }
    public String getProxyDbType() { return proxyDbType; }
    public int getProxyPort() { return proxyPort; }

    public List<String> riskDeniedOperationsList() {
        return splitCsv(riskDeniedOperations);
    }

    public List<String> riskDeniedStatementKeywordsList() {
        return splitCsv(riskDeniedStatementKeywords);
    }

    public String getAuditSpoolDir() {
        return auditSpoolDir;
    }

    public String getAuditFileName() {
        return auditFileName;
    }

    /** True when jdbc sink URL is configured (password never exposed via console status). */
    public boolean isAuditJdbcConfigured() {
        return auditJdbcUrl != null && !auditJdbcUrl.isBlank();
    }

    /** Internal browse / shipper use only — never put in API JSON. */
    public String getAuditJdbcUrl() {
        return auditJdbcUrl;
    }

    public String getAuditJdbcUsername() {
        return auditJdbcUsername;
    }

    public String getAuditJdbcPassword() {
        return auditJdbcPassword;
    }

    public String getAuditJdbcTable() {
        return auditJdbcTable != null && !auditJdbcTable.isBlank() ? auditJdbcTable : "gateway_audit_record";
    }

    public boolean isAuditShipperRunning() {
        return auditShipper != null && auditShipper.isRunning();
    }

    /** Best-effort spool file size hint (bytes); null when audit off or unavailable. */
    public Long getAuditSpoolBytesHint() {
        if (!auditEnabled || auditSpool == null) {
            return null;
        }
        try {
            var file = auditSpool.activeFile();
            if (file != null && java.nio.file.Files.isRegularFile(file)) {
                return java.nio.file.Files.size(file);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public boolean isPoolEnabled() {
        return poolEnabled;
    }

    public int getPoolMaxIdle() {
        return poolMaxIdle;
    }
}
