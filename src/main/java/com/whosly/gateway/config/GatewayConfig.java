package com.whosly.gateway.config;

import com.whosly.gateway.adapter.AbstractProtocolAdapter;
import com.whosly.gateway.adapter.MySqlProtocolAdapter;
import com.whosly.gateway.adapter.PostgreSQLProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.ClientAddressPolicy;
import com.whosly.gateway.adapter.protocol.CidrClientAddressPolicy;
import com.whosly.gateway.parser.DruidSqlParser;
import com.whosly.gateway.parser.SqlParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * Gateway Config implementation.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@Configuration
public class GatewayConfig {

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

    @Bean
    public SqlParser sqlParser() {
        return new DruidSqlParser();
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
