package com.whosly.gateway.adapter.protocol;

import java.io.IOException;

/**
 * 通用协议数据包处理接口
 * 
 * @param <T> 协议特定的握手信息类型
 */
public interface ProtocolPacket<T> {
    
    /**
     * 创建协议数据包
     * 
     * @param payload 数据包载荷
     * @param sequenceId 序列号
     * @return 完整的协议数据包
     */
    byte[] createPacket(byte[] payload, int sequenceId);
    
    /**
     * 读取协议数据包
     * 
     * @param inputStream 输入流
     * @return 数据包信息，包含载荷和序列号
     * @throws IOException 读取异常
     */
    PacketInfo readPacket(java.io.InputStream inputStream) throws IOException;
    
    /**
     * 创建握手包
     * 
     * @param connection 数据库连接，用于获取实际的数据库版本
     * @return 握手包载荷
     */
    byte[] createHandshakePacket(java.sql.Connection connection);
    
    /**
     * 解析客户端认证包
     * 
     * @param payload 认证包载荷
     * @return 认证信息
     */
    T parseAuthPacket(byte[] payload);
    
    /**
     * 创建认证成功包
     * 
     * @param sequenceId 序列号
     * @return 认证成功包
     */
    byte[] createOkPacket(int sequenceId);
    
    /**
     * 创建错误包
     * 
     * @param errorCode 错误码
     * @param sqlState SQL状态
     * @param errorMessage 错误消息
     * @param sequenceId 序列号
     * @return 错误包
     */
    byte[] createErrorPacket(int errorCode, String sqlState, String errorMessage, int sequenceId);
    
    /**
     * 数据包信息类
     */
    class PacketInfo {
        private final byte[] payload;
        private final int sequenceId;
        
        public PacketInfo(byte[] payload, int sequenceId) {
            this.payload = payload;
            this.sequenceId = sequenceId;
        }
        
        public byte[] getPayload() {
            return payload;
        }
        
        public int getSequenceId() {
            return sequenceId;
        }
    }
}