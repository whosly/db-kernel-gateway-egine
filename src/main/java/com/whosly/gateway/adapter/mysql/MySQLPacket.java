package com.whosly.gateway.adapter.mysql;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * MySQL协议数据包处理类
 */
public class MySQLPacket {
    public static final int MAX_PACKET_LENGTH = 0xFFFFFF; // 16MB - 1
    
    /**
     * 构造MySQL数据包
     * 
     * @param payload 数据包载荷
     * @param sequenceId 序列号
     * @return 完整的MySQL数据包
     */
    public static byte[] createPacket(byte[] payload, int sequenceId) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // 数据包长度（3字节）
        baos.write(payload.length & 0xFF);
        baos.write((payload.length >> 8) & 0xFF);
        baos.write((payload.length >> 16) & 0xFF);
        
        // 序列号（1字节）
        baos.write(sequenceId & 0xFF);
        
        // 载荷
        try {
            baos.write(payload);
        } catch (IOException e) {
            // 不应该发生
            throw new RuntimeException("Failed to create MySQL packet", e);
        }
        
        return baos.toByteArray();
    }
    
    /**
     * 读取MySQL数据包
     * 
     * @param inputStream 输入流
     * @return 数据包信息，包含载荷和序列号
     * @throws IOException 读取异常
     */
    public static PacketInfo readPacket(java.io.InputStream inputStream) throws IOException {
        // 读取数据包头部（4字节）
        byte[] header = new byte[4];
        int bytesRead = inputStream.read(header);
        if (bytesRead != 4) {
            throw new IOException("Failed to read packet header");
        }
        
        // 解析数据包长度（3字节）
        int packetLength = (header[0] & 0xFF) | 
                          ((header[1] & 0xFF) << 8) | 
                          ((header[2] & 0xFF) << 16);
        
        // 解析序列号（1字节）
        int sequenceId = header[3] & 0xFF;
        
        // 读取载荷
        byte[] payload = new byte[packetLength];
        int totalRead = 0;
        while (totalRead < packetLength) {
            int read = inputStream.read(payload, totalRead, packetLength - totalRead);
            if (read == -1) {
                throw new IOException("Unexpected end of stream");
            }
            totalRead += read;
        }
        
        return new PacketInfo(payload, sequenceId);
    }
    
    /**
     * 数据包信息类
     */
    public static class PacketInfo {
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
    
    /**
     * 写入长度编码的整数
     * 
     * @param value 值
     * @return 长度编码的字节数组
     */
    public static byte[] writeLengthEncodedInteger(long value) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (value < 251) {
            baos.write((int) value);
        } else if (value < 65536) {
            baos.write(0xFC);
            baos.write((int) (value & 0xFF));
            baos.write((int) ((value >> 8) & 0xFF));
        } else if (value < 16777216) {
            baos.write(0xFD);
            baos.write((int) (value & 0xFF));
            baos.write((int) ((value >> 8) & 0xFF));
            baos.write((int) ((value >> 16) & 0xFF));
        } else {
            baos.write(0xFE);
            for (int i = 0; i < 8; i++) {
                baos.write((int) ((value >> (i * 8)) & 0xFF));
            }
        }
        
        return baos.toByteArray();
    }
    
    /**
     * 写入长度编码的字符串
     * 
     * @param str 字符串
     * @return 长度编码的字节数组
     */
    public static byte[] writeLengthEncodedString(String str) {
        byte[] bytes = str.getBytes();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            baos.write(writeLengthEncodedInteger(bytes.length));
            baos.write(bytes);
        } catch (IOException e) {
            // 不应该发生
            throw new RuntimeException("Failed to write length encoded string", e);
        }
        
        return baos.toByteArray();
    }
}