package com.whosly.gateway.config;

import com.whosly.gateway.adapter.AbstractProtocolAdapter;
import com.whosly.gateway.adapter.MySqlProtocolAdapter;
import com.whosly.gateway.adapter.PostgreSQLProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.ClientAddressPolicy;
import com.whosly.gateway.adapter.protocol.CidrClientAddressPolicy;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficObserver;
import com.whosly.gateway.adapter.protocol.RewriteLimits;
import com.whosly.gateway.audit.AuditDurability;
import com.whosly.gateway.audit.AuditSpool;
import com.whosly.gateway.audit.AuditSpoolConfig;
import com.whosly.gateway.audit.SpoolingTrafficObserver;
import com.whosly.gateway.parser.DruidSqlParser;
import com.whosly.gateway.parser.SqlParser;
import com.whosly.gateway.parser.StatementClassifier;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Gateway Config implementation.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@Configuration
public class GatewayConfig implements DisposableBean {

    // 数据库类型配置
    @Value("${gateway.proxy-db-type:mysql}")
    private String proxyDbType;
    
    // 代理端口
    @Value("${gateway.proxy-port:3307}")
    private int proxyPort;
    
    // 目标数据库配置
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

    // 连接生命周期治理
    @Value("${gateway.max-connections:200}")
    private int maxConnections;

    @Value("${gateway.idle-timeout-seconds:0}")
    private long idleTimeoutSeconds;

    // 客户端地址白名单，逗号分隔的 CIDR；留空表示不限制
    @Value("${gateway.allowed-client-cidrs:}")
    private String allowedClientCidrs;

    // 改写边界：按整条消息改写时的最大消息字节数与最长持有毫秒数
    @Value("${gateway.rewrite.max-message-bytes:1048576}")
    private int rewriteMaxMessageBytes;

    @Value("${gateway.rewrite.max-hold-millis:1000}")
    private long rewriteMaxHoldMillis;

    // 审计留痕：默认关闭，启用后每条语句先落本地 spool 再转发
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

    /** Opened once per process when auditing is enabled; closed on shutdown. */
    private AuditSpool auditSpool;

    @Bean
    public SqlParser sqlParser() {
        return new DruidSqlParser();
    }

    /**
     * Bounds on the delay and memory a rewrite may use to assemble one message.
     *
     * <p>Messages that do not need rewriting are still forwarded immediately; only
     * an in-flight rewrite holds bytes, and exceeding either bound is rejected
     * rather than forwarded unmasked (rule 8.2).</p>
     */
    @Bean
    public RewriteLimits rewriteLimits() {
        return new RewriteLimits(rewriteMaxMessageBytes, rewriteMaxHoldMillis);
    }

    /**
     * Audit trail sink.
     *
     * <p>Statements are masked before they are spooled, so the trail never stores
     * values the masking policy hides (rule 8.2/8.5). The sink is mandatory: a
     * statement that cannot be recorded makes the operation fail instead of running
     * unaudited.</p>
     *
     * <p>Auditing is off by default. Turning it on changes what the gateway
     * guarantees — it gains a durable dependency and can deny traffic when the spool
     * is unavailable — so it is never enabled implicitly.</p>
     */
    @Bean
    public DatabaseTrafficObserver databaseTrafficObserver() throws IOException {
        if (!auditEnabled) {
            return DatabaseTrafficObserver.noop();
        }
        auditSpool = new AuditSpool(auditSpoolConfig());
        StatementClassifier classifier = auditGradeWrites ? new StatementClassifier(sqlParser()) : null;
        return DatabaseTrafficObserver.masking(new SpoolingTrafficObserver(auditSpool, classifier));
    }

    private AuditSpoolConfig auditSpoolConfig() {
        AuditDurability durability = switch (auditDurability.toLowerCase(Locale.ROOT)) {
            case "strict" -> AuditDurability.STRICT;
            case "window" -> AuditDurability.WINDOW;
            default -> throw new IllegalArgumentException(
                    "Unsupported gateway.audit.durability: " + auditDurability);
        };
        return new AuditSpoolConfig(Path.of(auditSpoolDir), auditFileName, durability, auditBatchSize,
                auditMaxBatchDelayMillis, auditMaxPendingRecords, auditMaxEnqueueWaitMillis, auditMaxSpoolBytes);
    }

    /**
     * Flushes the audit spool on shutdown: records that were accepted must reach the
     * disk, or the trail would end with a hole exactly when the process stopped
     * (rule 8.5).
     */
    @Override
    public void destroy() {
        if (auditSpool != null) {
            auditSpool.close();
        }
    }
    
    @Bean
    public ProtocolAdapter protocolAdapter() {
        // 根据配置的数据库类型创建相应的协议适配器
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
        // 设置目标数据库信息
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
        // 设置目标数据库信息
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
        try {
            adapter.setDatabaseTrafficObserver(databaseTrafficObserver());
        } catch (IOException e) {
            /*
             * A configured audit trail that cannot be opened must stop startup:
             * running without it would produce an incomplete trail while the
             * deployment believes it has a complete one (rule 8.5).
             */
            throw new IllegalStateException("Audit spool could not be opened", e);
        }
    }

    private ClientAddressPolicy clientAddressPolicy() {
        if (allowedClientCidrs == null || allowedClientCidrs.isBlank()) {
            return ClientAddressPolicy.allowAll();
        }
        return CidrClientAddressPolicy.of(Arrays.asList(allowedClientCidrs.split(",")));
    }
    
    // Getter methods for target database configuration
    public String getTargetHost() {
        return targetHost;
    }
    
    public int getTargetPort() {
        return targetPort;
    }
    
    public String getTargetUsername() {
        return targetUsername;
    }
    
    public String getTargetPassword() {
        return targetPassword;
    }
    
    public String getTargetDatabase() {
        return targetDatabase;
    }
    
    public String getProxyDbType() {
        return proxyDbType;
    }
    
    public int getProxyPort() {
        return proxyPort;
    }
}
