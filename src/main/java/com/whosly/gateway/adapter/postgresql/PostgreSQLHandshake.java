package com.whosly.gateway.adapter.postgresql;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;

/**
 * PostgreSQL握手和认证处理类
 */
public class PostgreSQLHandshake {
    
    /**
     * 认证信息类
     */
    public static class AuthInfo {
        private String username;
        private String database;
        private boolean sslRequest = false;
        
        public String getUsername() {
            return username;
        }
        
        public void setUsername(String username) {
            this.username = username;
        }
        
        public String getDatabase() {
            return database;
        }
        
        public void setDatabase(String database) {
            this.database = database;
        }
        
        public boolean isSslRequest() {
            return sslRequest;
        }
        
        public void setSslRequest(boolean sslRequest) {
            this.sslRequest = sslRequest;
        }
        
        @Override
        public String toString() {
            return "AuthInfo{username='" + username + "', database='" + database + "', sslRequest=" + sslRequest + "}";
        }
    }
    
    /**
     * 创建服务器握手初始化包
     * 
     * @param connection 数据库连接，用于获取实际的数据库版本
     * @return 握手初始化包
     */
    public static byte[] createStartupMessage(Connection connection) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        try {
            // 协议版本（4字节）
            baos.write((PostgreSQLPacket.PROTOCOL_VERSION >> 24) & 0xFF);
            baos.write((PostgreSQLPacket.PROTOCOL_VERSION >> 16) & 0xFF);
            baos.write((PostgreSQLPacket.PROTOCOL_VERSION >> 8) & 0xFF);
            baos.write(PostgreSQLPacket.PROTOCOL_VERSION & 0xFF);
            
            // 参数对：user
            baos.write("user".getBytes(StandardCharsets.UTF_8));
            baos.write(0); // null终止符
            
            // 获取数据库用户名
            String username = "unknown";
            try {
                if (connection != null && !connection.isClosed()) {
                    username = connection.getMetaData().getUserName();
                }
            } catch (SQLException e) {
                // 忽略异常，使用默认值
            }
            
            baos.write(username.getBytes(StandardCharsets.UTF_8));
            baos.write(0); // null终止符
            
            // 参数对：database
            baos.write("database".getBytes(StandardCharsets.UTF_8));
            baos.write(0); // null终止符
            
            // 获取数据库名称
            String databaseName = "postgres";
            try {
                if (connection != null && !connection.isClosed()) {
                    databaseName = connection.getCatalog();
                }
            } catch (SQLException e) {
                // 忽略异常，使用默认值
            }
            
            baos.write(databaseName.getBytes(StandardCharsets.UTF_8));
            baos.write(0); // null终止符
            
            // 参数对：client_encoding
            baos.write("client_encoding".getBytes(StandardCharsets.UTF_8));
            baos.write(0); // null终止符
            baos.write("UTF8".getBytes(StandardCharsets.UTF_8));
            baos.write(0); // null终止符
            
            // 参数对：DateStyle
            baos.write("DateStyle".getBytes(StandardCharsets.UTF_8));
            baos.write(0); // null终止符
            baos.write("ISO".getBytes(StandardCharsets.UTF_8));
            baos.write(0); // null终止符
            
            // 参数对：TimeZone
            baos.write("TimeZone".getBytes(StandardCharsets.UTF_8));
            baos.write(0); // null终止符
            baos.write("UTC".getBytes(StandardCharsets.UTF_8));
            baos.write(0); // null终止符
            
            // 参数对：extra_float_digits
            baos.write("extra_float_digits".getBytes(StandardCharsets.UTF_8));
            baos.write(0); // null终止符
            baos.write("2".getBytes(StandardCharsets.UTF_8));
            baos.write(0); // null终止符
            
            // 消息结束
            baos.write(0); // null终止符
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to create startup message", e);
        }
        
        return baos.toByteArray();
    }
    
    /**
     * 解析客户端启动消息
     * 
     * @param payload 启动消息载荷
     * @return 认证信息
     */
    public static AuthInfo parseStartupMessage(byte[] payload) {
        AuthInfo authInfo = new AuthInfo();
        
        try {
            // 跳过前4字节的协议版本
            int pos = 4;
            
            // 解析参数对
            while (pos < payload.length && payload[pos] != 0) {
                // 读取参数名称
                int nameStart = pos;
                while (pos < payload.length && payload[pos] != 0) {
                    pos++;
                }
                if (pos >= payload.length) break;
                
                String paramName = new String(payload, nameStart, pos - nameStart, StandardCharsets.UTF_8);
                pos++; // 跳过null终止符
                
                // 读取参数值
                int valueStart = pos;
                while (pos < payload.length && payload[pos] != 0) {
                    pos++;
                }
                if (pos >= payload.length) break;
                
                String paramValue = new String(payload, valueStart, pos - valueStart, StandardCharsets.UTF_8);
                pos++; // 跳过null终止符
                
                // 根据参数名称设置认证信息
                switch (paramName) {
                    case "user":
                        authInfo.setUsername(paramValue);
                        break;
                    case "database":
                        authInfo.setDatabase(paramValue);
                        break;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse startup message: " + e.getMessage(), e);
        }
        
        return authInfo;
    }
    
    /**
     * 解析SSL请求
     * 
     * @param payload SSL请求载荷
     * @return 认证信息
     */
    public static AuthInfo parseSslRequest(byte[] payload) {
        AuthInfo authInfo = new AuthInfo();
        authInfo.setSslRequest(true);
        return authInfo;
    }
}