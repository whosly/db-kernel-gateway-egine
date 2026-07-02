package com.whosly.gateway.config;

import com.whosly.gateway.adapter.MySqlProtocolAdapter;
import com.whosly.gateway.adapter.PostgreSQLProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.parser.DruidSqlParser;
import com.whosly.gateway.parser.SqlParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    
    @Value("${gateway.target.username:root}")
    private String targetUsername;
    
    @Value("${gateway.target.password:password}")
    private String targetPassword;
    
    @Value("${gateway.target.database:demo}")
    private String targetDatabase;

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
            case "oracle":
                // 未来可以支持Oracle
                return createMySqlProtocolAdapter(); // 临时返回MySQL适配器
            default:
                return createMySqlProtocolAdapter();
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
        return adapter;
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