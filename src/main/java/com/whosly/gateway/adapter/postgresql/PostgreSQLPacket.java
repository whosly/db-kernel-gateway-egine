package com.whosly.gateway.adapter.postgresql;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;

/**
 * PostgreSQL协议数据包处理类
 */
public class PostgreSQLPacket {
    // PostgreSQL协议常量
    public static final int PROTOCOL_VERSION = 196608; // 3.0
    
    /**
     * 构造PostgreSQL数据包
     * 
     * @param payload 数据包载荷
     * @return 完整的PostgreSQL数据包
     */
    public static byte[] createPacket(byte[] payload) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // PostgreSQL消息长度（4字节，包括长度字段本身）
        int messageLength = payload.length + 4;
        baos.write((messageLength >> 24) & 0xFF);
        baos.write((messageLength >> 16) & 0xFF);
        baos.write((messageLength >> 8) & 0xFF);
        baos.write(messageLength & 0xFF);
        
        // 载荷
        baos.write(payload, 0, payload.length);
        
        return baos.toByteArray();
    }
    
    /**
     * 构造无载荷的PostgreSQL数据包（如ReadyForQuery消息）
     * 
     * @param type 消息类型
     * @return 完整的PostgreSQL数据包
     */
    public static byte[] createPacket(char type) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // 消息类型（1字节）
        baos.write(type);
        
        // 消息长度（4字节，包括长度字段本身）
        baos.write(0);
        baos.write(0);
        baos.write(0);
        baos.write(4);
        
        return baos.toByteArray();
    }
    
    /**
     * 构造带载荷的PostgreSQL数据包
     * 
     * @param type 消息类型
     * @param payload 数据包载荷
     * @return 完整的PostgreSQL数据包
     */
    public static byte[] createPacket(char type, byte[] payload) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // 消息类型（1字节）
        baos.write(type);
        
        // 消息长度（4字节，包括长度字段本身和类型字段）
        int messageLength = payload.length + 4;
        baos.write((messageLength >> 24) & 0xFF);
        baos.write((messageLength >> 16) & 0xFF);
        baos.write((messageLength >> 8) & 0xFF);
        baos.write(messageLength & 0xFF);
        
        // 载荷
        baos.write(payload, 0, payload.length);
        
        return baos.toByteArray();
    }
    
    /**
     * 获取数据库版本
     * 
     * @param connection 数据库连接
     * @return 数据库版本字符串
     */
    private static String getDatabaseVersion(Connection connection) {
        try {
            if (connection != null && !connection.isClosed()) {
                DatabaseMetaData metaData = connection.getMetaData();
                return metaData.getDatabaseProductVersion();
            }
        } catch (SQLException e) {
            // 如果无法获取版本信息，使用默认版本
            e.printStackTrace();
        }
        return "13.0"; // 默认版本
    }
    
    /**
     * 创建服务器握手初始化包（AuthenticationOk）
     * 
     * @return 握手初始化包
     */
    public static byte[] createAuthenticationOkPacket() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // 消息类型：'R' (Authentication)
        baos.write('R');
        
        // 消息长度（长度字段本身4字节 + 认证类型4字节 = 8字节）
        // 注意：长度不包括消息类型字节
        baos.write(0);
        baos.write(0);
        baos.write(0);
        baos.write(8);
        
        // 认证类型：0表示认证成功（AuthenticationOk）
        baos.write(0);
        baos.write(0);
        baos.write(0);
        baos.write(0);
        
        return baos.toByteArray();
    }
    
    /**
     * 创建认证请求包（AuthenticationRequest）
     * 
     * @return 认证请求包
     */
    public static byte[] createAuthenticationRequestPacket() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // 消息类型：'R' (Authentication)
        baos.write('R');
        
        // 消息长度（8字节：类型(1) + 长度(4) + 认证类型(4)）
        baos.write(0);
        baos.write(0);
        baos.write(0);
        baos.write(8);
        
        // 认证类型：3表示明文密码认证
        baos.write(0);
        baos.write(0);
        baos.write(0);
        baos.write(3);
        
        return baos.toByteArray();
    }
    
    /**
     * 创建参数状态包（ParameterStatus）
     * 
     * @param name 参数名称
     * @param value 参数值
     * @return 参数状态包
     */
    public static byte[] createParameterStatusPacket(String name, String value) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // 消息类型：'S' (ParameterStatus)
        baos.write('S');
        
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        
        // 消息长度（长度字段本身4字节 + 名称长度 + null终止符1字节 + 值长度 + null终止符1字节）
        // 注意：长度不包括消息类型字节
        int messageLength = 4 + nameBytes.length + 1 + valueBytes.length + 1;
        baos.write((messageLength >> 24) & 0xFF);
        baos.write((messageLength >> 16) & 0xFF);
        baos.write((messageLength >> 8) & 0xFF);
        baos.write(messageLength & 0xFF);
        
        // 参数名称
        baos.write(nameBytes, 0, nameBytes.length);
        baos.write(0); // null终止符
        
        // 参数值
        baos.write(valueBytes, 0, valueBytes.length);
        baos.write(0); // null终止符
        
        return baos.toByteArray();
    }
    
    /**
     * 创建后端密钥数据包（BackendKeyData）
     * 
     * @param processId 进程ID
     * @param secretKey 秘密密钥
     * @return 后端密钥数据包
     */
    public static byte[] createBackendKeyDataPacket(int processId, int secretKey) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // 消息类型：'K' (BackendKeyData)
        baos.write('K');
        
        // 消息长度（长度字段本身4字节 + 进程ID 4字节 + 秘密密钥4字节 = 12字节）
        // 注意：长度不包括消息类型字节
        baos.write(0);
        baos.write(0);
        baos.write(0);
        baos.write(12);
        
        // 进程ID（大端序）
        baos.write((processId >> 24) & 0xFF);
        baos.write((processId >> 16) & 0xFF);
        baos.write((processId >> 8) & 0xFF);
        baos.write(processId & 0xFF);
        
        // 秘密密钥（大端序）
        baos.write((secretKey >> 24) & 0xFF);
        baos.write((secretKey >> 16) & 0xFF);
        baos.write((secretKey >> 8) & 0xFF);
        baos.write(secretKey & 0xFF);
        
        return baos.toByteArray();
    }
    
    /**
     * 创建就绪查询包（ReadyForQuery）
     * 
     * @param transactionStatus 事务状态 ('I' = 空闲, 'T' = 事务中, 'E' = 事务失败)
     * @return 就绪查询包
     */
    public static byte[] createReadyForQueryPacket(char transactionStatus) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // 消息类型：'Z' (ReadyForQuery)
        baos.write('Z');
        
        // 消息长度（长度字段本身4字节 + 事务状态1字节 = 5字节）
        // 注意：长度不包括消息类型字节
        baos.write(0);
        baos.write(0);
        baos.write(0);
        baos.write(5);
        
        // 事务状态 ('I' = idle, 'T' = in transaction, 'E' = failed transaction)
        baos.write(transactionStatus);
        
        return baos.toByteArray();
    }
    
    /**
     * 创建错误响应包（ErrorResponse）
     * 
     * @param severity 错误严重性
     * @param code 错误代码
     * @param message 错误消息
     * @return 错误响应包
     */
    public static byte[] createErrorResponsePacket(String severity, String code, String message) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // 消息类型：'E' (ErrorResponse)
        baos.write('E');
        
        // 构建字段内容（每个字段格式：字段类型(1字节) + 字符串 + null终止符）
        byte[] severityBytes = severity.getBytes(StandardCharsets.UTF_8);
        byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        
        // 消息长度 = 长度字段本身(4) + 'S'(1) + severity + null(1) + 'C'(1) + code + null(1) + 'M'(1) + message + null(1) + 结束null(1)
        int messageLength = 4 + 1 + severityBytes.length + 1 + 1 + codeBytes.length + 1 + 1 + messageBytes.length + 1 + 1;
        baos.write((messageLength >> 24) & 0xFF);
        baos.write((messageLength >> 16) & 0xFF);
        baos.write((messageLength >> 8) & 0xFF);
        baos.write(messageLength & 0xFF);
        
        // 严重性字段 (S)
        baos.write('S');
        baos.write(severityBytes, 0, severityBytes.length);
        baos.write(0); // null终止符
        
        // 错误代码字段 (C)
        baos.write('C');
        baos.write(codeBytes, 0, codeBytes.length);
        baos.write(0); // null终止符
        
        // 错误消息字段 (M)
        baos.write('M');
        baos.write(messageBytes, 0, messageBytes.length);
        baos.write(0); // null终止符
        
        // 消息结束标记
        baos.write(0);
        
        return baos.toByteArray();
    }
    
    /**
     * 创建命令完成包（CommandComplete）
     * 
     * @param commandTag 命令标签
     * @return 命令完成包
     */
    public static byte[] createCommandCompletePacket(String commandTag) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // 消息类型：'C' (CommandComplete)
        baos.write('C');
        
        byte[] tagBytes = commandTag.getBytes(StandardCharsets.UTF_8);
        
        // 消息长度（长度(4) + 标签长度 + null终止符(1)）
        int messageLength = 4 + tagBytes.length + 1;
        baos.write((messageLength >> 24) & 0xFF);
        baos.write((messageLength >> 16) & 0xFF);
        baos.write((messageLength >> 8) & 0xFF);
        baos.write(messageLength & 0xFF);
        
        // 命令标签
        baos.write(tagBytes, 0, tagBytes.length);
        baos.write(0); // null终止符
        
        return baos.toByteArray();
    }
}