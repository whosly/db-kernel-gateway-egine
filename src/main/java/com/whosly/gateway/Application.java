package com.whosly.gateway;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

/**
 * Main application class for the Multi-Protocol Database Gateway Engine.
 * 
 * This gateway engine provides unified access to various database systems
 * through different protocols, using Alibaba Druid for SQL parsing.
 */
@SpringBootApplication
public class Application {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    @Autowired
    private ProtocolAdapter protocolAdapter;
    
    // 注入GatewayConfig以获取目标数据库配置信息
    @Autowired
    private GatewayConfig gatewayConfig;
    
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
    
    @EventListener
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // Start the protocol adapter when the application context is refreshed
        String protocolType = gatewayConfig.getProxyDbType().toUpperCase();
        log.info("Starting {} protocol adapter...", protocolType);
        protocolAdapter.start();
        
        // 输出代理地址信息和目标数据库信息
        logInfo();
    }
    
    /**
     * 输出代理地址信息和目标数据库信息
     */
    private void logInfo() {
        // 获取协议类型
        String protocolType = gatewayConfig.getProxyDbType().toUpperCase();
        
        // 输出代理信息
        log.info("{} Protocol Proxy Info:", protocolType);
        log.info("  Proxy Address: localhost:{}", protocolAdapter.getDefaultPort());
        
        // 输出目标数据库信息
        log.info("Target Database Info:");
        log.info("  Host: {}", gatewayConfig.getTargetHost());
        log.info("  Port: {}", gatewayConfig.getTargetPort());
        log.info("  Username: {}", gatewayConfig.getTargetUsername());
        log.info("  Database: {}", gatewayConfig.getTargetDatabase());
        
        // 调试信息，显示实际的配置值
        log.info("=== Configuration Debug Info ===");
        log.info("Proxy DB Type: {}", gatewayConfig.getProxyDbType());
        log.info("Proxy Port: {}", gatewayConfig.getProxyPort());
        log.info("Target Host (from config): {}", gatewayConfig.getTargetHost());
        log.info("Target Port (from config): {}", gatewayConfig.getTargetPort());
        log.info("Target Username (from config): {}", gatewayConfig.getTargetUsername());
        log.info("Target Database (from config): {}", gatewayConfig.getTargetDatabase());
        log.info("=== End Configuration Debug Info ===");
        
        // 注意：出于安全考虑，不输出密码
    }
}