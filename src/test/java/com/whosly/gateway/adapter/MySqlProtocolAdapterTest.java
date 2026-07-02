package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.mysql.MySQLHandshake;
import com.whosly.gateway.adapter.mysql.MySQLPacket;
import com.whosly.gateway.adapter.mysql.MySQLResultSet;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MySqlProtocolAdapterTest {

    @Test
    void testProtocolAdapterCreation() {
        MySqlProtocolAdapter adapter = new MySqlProtocolAdapter();
        
        assertThat(adapter).isNotNull();
        assertThat(adapter.getProtocolName()).isEqualTo("MySQL");
        assertThat(adapter.getDefaultPort()).isEqualTo(3307); // 更新为新的默认端口
        assertThat(adapter.isRunning()).isFalse();
    }

    @Test
    void testStartAndStop() {
        MySqlProtocolAdapter adapter = new MySqlProtocolAdapter();
        
        // Start the adapter
        adapter.start();
        // Note: In a real test environment, we might not be able to actually start the server
        // So we'll just test that the method doesn't throw an exception
        
        // Stop the adapter
        adapter.stop();
        // Same here, we're just testing that the method doesn't throw an exception
    }
    
    @Test
    void testMySQLPacketCreation() {
        byte[] payload = "test payload".getBytes();
        byte[] packet = MySQLPacket.createPacket(payload, 1);
        
        // Check packet length (payload + 4 bytes header)
        assertThat(packet.length).isEqualTo(payload.length + 4);
        
        // Check header fields
        assertThat(packet[0] & 0xFF).isEqualTo(payload.length & 0xFF);
        assertThat(packet[1] & 0xFF).isEqualTo((payload.length >> 8) & 0xFF);
        assertThat(packet[2] & 0xFF).isEqualTo((payload.length >> 16) & 0xFF);
        assertThat(packet[3] & 0xFF).isEqualTo(1); // sequence ID
    }
    
    @Test
    void testHandshakePacketCreation() {
        // 创建一个模拟的数据库连接用于测试
        Connection mockConnection = mock(Connection.class);
        byte[] handshakeData = MySQLHandshake.createHandshakePacket(mockConnection);
        assertThat(handshakeData).isNotNull();
        assertThat(handshakeData.length).isGreaterThan(0);
    }
    
    @Test
    void testOkPacketCreation() {
        byte[] okPacket = MySQLHandshake.createOkPacket(1);
        assertThat(okPacket).isNotNull();
        assertThat(okPacket.length).isGreaterThan(4); // At least header + payload
    }
    
    @Test
    void testErrorPacketCreation() {
        byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Test error", 1);
        assertThat(errorPacket).isNotNull();
        assertThat(errorPacket.length).isGreaterThan(4); // At least header + payload
    }
    
    @Test
    void testEofPacketCreation() {
        byte[] eofPacket = MySQLResultSet.createEofPacket(1);
        assertThat(eofPacket).isNotNull();
        assertThat(eofPacket.length).isGreaterThan(4); // At least header + payload
    }
}