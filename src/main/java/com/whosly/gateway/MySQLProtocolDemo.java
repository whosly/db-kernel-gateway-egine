package com.whosly.gateway;

import com.whosly.gateway.adapter.MySqlProtocolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MySQL协议适配器演示程序
 * 展示如何启动和使用MySQL协议适配器
 */
public class MySQLProtocolDemo {
    private static final Logger log = LoggerFactory.getLogger(MySQLProtocolDemo.class);
    
    public static void main(String[] args) {
        log.info("Starting MySQL Protocol Adapter Demo");
        
        // 创建MySQL协议适配器实例
        MySqlProtocolAdapter mysqlAdapter = new MySqlProtocolAdapter();
        
        // 启动适配器
        mysqlAdapter.start();
        
        // 检查适配器是否正在运行
        if (mysqlAdapter.isRunning()) {
            log.info("MySQL Protocol Adapter is running on port {}", mysqlAdapter.getDefaultPort());
        } else {
            log.error("Failed to start MySQL Protocol Adapter");
            return;
        }
        
        // 保持程序运行一段时间以允许测试连接
        log.info("MySQL Protocol Adapter is now ready to accept connections");
        log.info("Connect to it using a MySQL client:");
        log.info("  mysql -h localhost -P 3306 -u anyuser -p");
        log.info("Press Ctrl+C to stop the demo");
        
        // 保持程序运行
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            log.info("Demo interrupted, shutting down...");
        } finally {
            // 停止适配器
            mysqlAdapter.stop();
            log.info("MySQL Protocol Adapter stopped");
        }
    }
}