package com.whosly.gateway.adapter;

import com.whosly.gateway.parser.SqlParser;
import com.whosly.gateway.service.DatabaseConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 抽象协议适配器基类
 * 
 * 提供协议适配器的通用实现，具体的协议适配器可以继承此类
 */
public abstract class AbstractProtocolAdapter implements ProtocolAdapter {
    
    protected static final Logger log = LoggerFactory.getLogger(AbstractProtocolAdapter.class);
    
    protected volatile boolean running = false;
    protected ServerSocket serverSocket;
    protected ExecutorService executorService;
    protected SqlParser sqlParser;
    protected DatabaseConnectionService databaseConnectionService;
    protected int port;
    protected String protocolName;
    
    // 目标数据库配置
    protected String targetHost = "localhost";
    protected int targetPort = 3306;
    protected String targetUsername = "root";
    protected String targetPassword = "password";
    protected String targetDatabase = "testdb";
    
    public AbstractProtocolAdapter(String protocolName, int defaultPort) {
        this.protocolName = protocolName;
        this.port = defaultPort;
        this.sqlParser = createSqlParser();
        this.databaseConnectionService = new DatabaseConnectionService();
    }
    
    /**
     * 创建SQL解析器
     * 
     * @return SQL解析器实例
     */
    protected abstract SqlParser createSqlParser();
    
    public void setPort(int port) {
        this.port = port;
    }
    
    public int getPort() {
        return this.port;
    }
    
    // 目标数据库配置的setter方法
    public void setTargetHost(String targetHost) {
        this.targetHost = targetHost;
    }
    
    public void setTargetPort(int targetPort) {
        this.targetPort = targetPort;
    }
    
    public void setTargetUsername(String targetUsername) {
        this.targetUsername = targetUsername;
    }
    
    public void setTargetPassword(String targetPassword) {
        this.targetPassword = targetPassword;
    }
    
    public void setTargetDatabase(String targetDatabase) {
        this.targetDatabase = targetDatabase;
    }
    
    @Override
    public String getProtocolName() {
        return protocolName;
    }
    
    @Override
    public int getDefaultPort() {
        return port;
    }
    
    @Override
    public void start() {
        if (running) {
            log.warn("{} protocol adapter is already running", protocolName);
            return;
        }
        
        try {
            serverSocket = new ServerSocket(port);
            executorService = Executors.newCachedThreadPool();
            running = true;
            
            log.info("Starting {} protocol adapter on port {}", protocolName, port);
            
            // Start accepting client connections
            executorService.submit(this::acceptConnections);
            
            log.info("{} protocol adapter started successfully", protocolName);
        } catch (IOException e) {
            log.error("Failed to start {} protocol adapter", protocolName, e);
            running = false;
        }
    }
    
    @Override
    public void stop() {
        if (!running) {
            log.warn("{} protocol adapter is not running", protocolName);
            return;
        }
        
        log.info("Stopping {} protocol adapter", protocolName);
        
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            
            if (executorService != null) {
                executorService.shutdown();
            }
        } catch (IOException e) {
            log.error("Error stopping {} protocol adapter", protocolName, e);
        }
        
        running = false;
        log.info("{} protocol adapter stopped successfully", protocolName);
    }
    
    @Override
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Accept client connections and handle them in separate threads.
     */
    protected void acceptConnections() {
        while (running && !serverSocket.isClosed()) {
            try {
                java.net.Socket clientSocket = serverSocket.accept();
                log.info("New client connection accepted from {}", clientSocket.getRemoteSocketAddress());
                
                // Handle each client in a separate thread
                executorService.submit(() -> handleClientConnection(clientSocket));
            } catch (IOException e) {
                if (running) {
                    log.error("Error accepting client connection", e);
                }
            }
        }
    }
    
    /**
     * Handle a client connection with full protocol support.
     * 
     * @param clientSocket the client socket
     */
    protected abstract void handleClientConnection(java.net.Socket clientSocket);
}