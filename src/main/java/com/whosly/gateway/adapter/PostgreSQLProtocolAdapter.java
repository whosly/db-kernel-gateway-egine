package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.postgresql.PostgreSQLHandshake;
import com.whosly.gateway.adapter.postgresql.PostgreSQLPacket;
import com.whosly.gateway.adapter.postgresql.PostgreSQLResultSet;
import com.whosly.gateway.parser.SqlParser;
import com.whosly.gateway.parser.DruidSqlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.sql.*;

/**
 * PostgreSQL protocol adapter implementation.
 * This adapter handles PostgreSQL client connections and proxies them to backend databases.
 */
public class PostgreSQLProtocolAdapter extends AbstractProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(PostgreSQLProtocolAdapter.class);
    private static final String PROTOCOL_NAME = "PostgreSQL";
    private static final int DEFAULT_PORT = 5432;
    
    public PostgreSQLProtocolAdapter() {
        super(PROTOCOL_NAME, DEFAULT_PORT);
    }
    
    @Override
    protected SqlParser createSqlParser() {
        return new DruidSqlParser();
    }
    
    @Override
    protected void handleClientConnection(Socket clientSocket) {
        Connection backendConnection = null;
        try {
            log.info("=== New PostgreSQL client connection from {} ===", clientSocket.getRemoteSocketAddress());
            
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();
            
            log.debug("Client socket input/output streams obtained");
            
            // 先读取客户端启动消息，获取客户端请求的数据库名
            PostgreSQLHandshake.AuthInfo authInfo = readStartupMessage(clientIn, clientOut);
            if (authInfo == null) {
                log.warn("Failed to read startup message from client");
                return;
            }
            
            // 检查是否是SSL请求
            if (authInfo.isSslRequest()) {
                log.info("SSL request received from client, sending rejection ('N')");
                // 发送拒绝SSL的响应
                clientOut.write('N');
                clientOut.flush();
                log.debug("SSL rejection sent, waiting for actual startup message");
                
                // 重新读取启动消息
                authInfo = readStartupMessage(clientIn, clientOut);
                if (authInfo == null) {
                    log.error("Failed to read startup message after SSL rejection");
                    return;
                }
                if (authInfo.isSslRequest()) {
                    log.error("Received another SSL request after rejection");
                    return;
                }
                log.info("Received actual startup message after SSL rejection");
            }
            
            // 使用客户端请求的数据库名，如果没有则使用配置的默认数据库
            String requestedDatabase = authInfo.getDatabase();
            if (requestedDatabase == null || requestedDatabase.isEmpty()) {
                requestedDatabase = targetDatabase;
            }
            
            // 连接到后端数据库
            String dbUrl = String.format("jdbc:postgresql://%s:%d/%s", 
                targetHost,
                targetPort,
                requestedDatabase);
            
            log.info("Connecting to backend database: {} with user: {}", dbUrl, targetUsername);
            backendConnection = databaseConnectionService.connectToDatabase(
                dbUrl, 
                targetUsername, 
                targetPassword
            );
            log.info("Successfully connected to backend database: {}", dbUrl);
            
            // 直接发送认证成功消息，跳过密码验证
            log.info("Sending authentication sequence to client (user: {}, database: {})", 
                authInfo.getUsername(), requestedDatabase);
            
            log.debug("Step 1: Sending AuthenticationOk packet");
            byte[] authOkPacket = PostgreSQLPacket.createAuthenticationOkPacket();
            clientOut.write(authOkPacket);
            clientOut.flush();
            
            log.debug("Step 2: Sending parameter status packets");
            sendParameterStatusPackets(clientOut, backendConnection);
            
            log.debug("Step 3: Sending backend key data packet");
            byte[] backendKeyDataPacket = PostgreSQLPacket.createBackendKeyDataPacket(12345, 67890);
            clientOut.write(backendKeyDataPacket);
            clientOut.flush();
            
            log.debug("Step 4: Sending ReadyForQuery packet");
            byte[] readyForQueryPacket = PostgreSQLPacket.createReadyForQueryPacket('I');
            clientOut.write(readyForQueryPacket);
            clientOut.flush();
            
            log.info("Authentication sequence completed successfully");
            
            // Proxy communication between client and backend
            log.info("Starting proxy communication");
            proxyConnection(clientSocket, clientIn, clientOut, backendConnection, requestedDatabase);
            log.info("Proxy communication ended");
        } catch (Exception e) {
            log.error("Error handling PostgreSQL client connection from {}", clientSocket.getRemoteSocketAddress(), e);
            try {
                // 尝试发送错误包给客户端
                try {
                    byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("FATAL", "08006", "Connection Error: " + e.getMessage());
                    clientSocket.getOutputStream().write(errorPacket);
                    clientSocket.getOutputStream().flush();
                } catch (Exception innerException) {
                    log.debug("Failed to send error packet to client: {}", innerException.getMessage());
                }
                clientSocket.close();
            } catch (IOException ioException) {
                log.error("Error closing client socket", ioException);
            }
        } finally {
            if (backendConnection != null) {
                try {
                    log.debug("Closing backend connection");
                    backendConnection.close();
                    log.debug("Backend connection closed");
                } catch (SQLException e) {
                    log.error("Error closing backend connection", e);
                }
            }
            try {
                if (!clientSocket.isClosed()) {
                    log.debug("Closing client socket");
                    clientSocket.close();
                    log.debug("Client socket closed");
                }
            } catch (IOException e) {
                log.error("Error closing client socket", e);
            }
        }
    }

    /**
     * 读取客户端启动消息
     */
    private PostgreSQLHandshake.AuthInfo readStartupMessage(InputStream clientIn, OutputStream clientOut) throws IOException {
        // Read message length
        byte[] lengthBytes = new byte[4];
        int bytesRead = clientIn.read(lengthBytes);
        if (bytesRead < 4) {
            log.warn("Failed to read message length, only got {} bytes", bytesRead);
            return null;
        }
        
        int messageLength = ((lengthBytes[0] & 0xFF) << 24) |
                           ((lengthBytes[1] & 0xFF) << 16) |
                           ((lengthBytes[2] & 0xFF) << 8) |
                           (lengthBytes[3] & 0xFF);
        
        log.debug("Received startup message with length: {}", messageLength);
        
        // Check for SSL request (special case: length = 8, content = 0x04d2162f)
        if (messageLength == 8) {
            byte[] sslBytes = new byte[4];
            bytesRead = clientIn.read(sslBytes);
            log.debug("Checking for SSL request: {} bytes read", bytesRead);
            if (bytesRead == 4 && 
                sslBytes[0] == 0x04 && sslBytes[1] == (byte)0xd2 && 
                sslBytes[2] == 0x16 && sslBytes[3] == 0x2f) {
                log.info("SSL request detected (magic number: 0x04d2162f)");
                PostgreSQLHandshake.AuthInfo authInfo = new PostgreSQLHandshake.AuthInfo();
                authInfo.setSslRequest(true);
                return authInfo;
            } else {
                log.debug("Not an SSL request, SSL bytes: 0x{}{}{}{}", 
                    String.format("%02x", sslBytes[0]),
                    String.format("%02x", sslBytes[1]),
                    String.format("%02x", sslBytes[2]),
                    String.format("%02x", sslBytes[3]));
            }
            // If it's not SSL request, continue with normal processing
        }
        
        // Read the rest of the message
        int remainingLength = messageLength - 4;
        log.debug("Reading remaining {} bytes of startup message", remainingLength);
        
        byte[] messageBytes = new byte[remainingLength];
        int totalRead = 0;
        while (totalRead < remainingLength) {
            int read = clientIn.read(messageBytes, totalRead, remainingLength - totalRead);
            if (read == -1) {
                log.warn("Unexpected end of stream while reading startup message");
                return null;
            }
            totalRead += read;
        }
        
        log.debug("Successfully read {} bytes of startup message", totalRead);
        
        // Parse startup message
        PostgreSQLHandshake.AuthInfo authInfo = PostgreSQLHandshake.parseStartupMessage(messageBytes);
        log.info("Parsed startup message: username={}, database={}", 
            authInfo.getUsername(), authInfo.getDatabase());
        
        return authInfo;
    }
    
    /**
     * 验证密码（简化实现）
     */
    private boolean validatePassword(String username, String password) {
        // 在实际实现中，这里应该查询数据库验证用户名和密码
        // 为简化起见，我们直接验证配置中的用户名和密码
        return username != null && 
               username.equals(targetUsername) && 
               password != null && 
               password.equals(targetPassword);
    }
    
    /**
     * Send parameter status packets to client
     */
    private void sendParameterStatusPackets(OutputStream clientOut, Connection backendConnection) throws IOException {
        // Send server version - 从后端数据库获取真实版本
        String serverVersion = "13.0"; // 默认值
        try {
            if (backendConnection != null && !backendConnection.isClosed()) {
                DatabaseMetaData metaData = backendConnection.getMetaData();
                // 获取完整的版本字符串，如 "PostgreSQL 15.6"
                String fullVersion = metaData.getDatabaseProductVersion();
                
                // 提取版本号部分（去掉"PostgreSQL"等前缀）
                // 例如："PostgreSQL 15.6 on x86_64..." -> "15.6"
                if (fullVersion != null) {
                    // 尝试提取版本号
                    String[] parts = fullVersion.split("\\s+");
                    for (String part : parts) {
                        if (part.matches("\\d+\\.\\d+.*")) {
                            // 找到版本号格式的部分，只取主版本号和次版本号
                            String[] versionParts = part.split("\\.");
                            if (versionParts.length >= 2) {
                                serverVersion = versionParts[0] + "." + versionParts[1];
                            } else {
                                serverVersion = versionParts[0] + ".0";
                            }
                            break;
                        }
                    }
                }
                log.debug("Backend PostgreSQL version: {} (from: {})", serverVersion, fullVersion);
            }
        } catch (SQLException e) {
            log.warn("Failed to get backend database version, using default: {}", serverVersion);
        }
        
        byte[] versionPacket = PostgreSQLPacket.createParameterStatusPacket("server_version", serverVersion);
        clientOut.write(versionPacket);
        clientOut.flush();
        
        // Send server encoding
        byte[] encodingPacket = PostgreSQLPacket.createParameterStatusPacket("server_encoding", "UTF8");
        clientOut.write(encodingPacket);
        clientOut.flush();
        
        // Send client encoding
        byte[] clientEncodingPacket = PostgreSQLPacket.createParameterStatusPacket("client_encoding", "UTF8");
        clientOut.write(clientEncodingPacket);
        clientOut.flush();
        
        // Send DateStyle
        byte[] dateStylePacket = PostgreSQLPacket.createParameterStatusPacket("DateStyle", "ISO, MDY");
        clientOut.write(dateStylePacket);
        clientOut.flush();
        
        // Send TimeZone
        byte[] timeZonePacket = PostgreSQLPacket.createParameterStatusPacket("TimeZone", "UTC");
        clientOut.write(timeZonePacket);
        clientOut.flush();
        
        // Send integer_datetimes
        byte[] integerDatetimesPacket = PostgreSQLPacket.createParameterStatusPacket("integer_datetimes", "on");
        clientOut.write(integerDatetimesPacket);
        clientOut.flush();
    }

    /**
     * 检查SQL是否包含数据库切换命令
     * 在Navicat中，切换数据库会发送类似 "SELECT current_database()" 的查询
     * 但实际切换是通过重新连接实现的
     * 这里我们需要检测客户端是否在查询不同数据库的对象
     */
    private String checkDatabaseSwitch(byte[] messageBytes, String currentDatabase) {
        try {
            int queryLength = messageBytes.length;
            if (queryLength > 0 && messageBytes[queryLength - 1] == 0) {
                queryLength--;
            }
            String sqlQuery = new String(messageBytes, 0, queryLength, "UTF-8").trim();
            
            // 检查是否有 \c database 或 \connect database 命令（psql命令）
            // 或者检查是否有显式的数据库切换
            // 注意：Navicat通常通过重新连接来切换数据库，而不是发送SQL命令
            // 所以这里主要是为了兼容psql等命令行工具
            
            return null; // 暂时不支持SQL级别的数据库切换
        } catch (Exception e) {
            log.debug("Error checking database switch: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 切换到新的数据库
     */
    private Connection switchDatabase(Connection oldConnection, String newDatabase) throws SQLException {
        // 关闭旧连接
        if (oldConnection != null && !oldConnection.isClosed()) {
            log.info("Closing connection to old database");
            oldConnection.close();
        }
        
        // 建立新连接
        String dbUrl = String.format("jdbc:postgresql://%s:%d/%s", 
            targetHost,
            targetPort,
            newDatabase);
        
        log.info("Switching to database: {}", newDatabase);
        Connection newConnection = databaseConnectionService.connectToDatabase(
            dbUrl, 
            targetUsername, 
            targetPassword
        );
        log.info("Successfully switched to database: {}", newDatabase);
        
        return newConnection;
    }
    
    /**
     * Proxy communication between client and backend database.
     */
    private void proxyConnection(Socket clientSocket, InputStream clientIn, OutputStream clientOut, Connection backendConnection, String initialDatabase) throws IOException {
        String currentDatabase = initialDatabase;
        Connection connection = backendConnection;
        
        try {
            while (!clientSocket.isClosed() && !Thread.currentThread().isInterrupted()) {
                // 先读取消息类型（1字节）
                int messageTypeByte = clientIn.read();
                if (messageTypeByte == -1) {
                    // 客户端断开连接
                    log.debug("Client disconnected");
                    return;
                }
                
                char messageType = (char) messageTypeByte;
                
                // 读取消息长度（4字节）
                byte[] lengthBytes = new byte[4];
                int bytesRead = clientIn.read(lengthBytes);
                if (bytesRead != 4) {
                    log.warn("Failed to read message length, expected 4 bytes but got {}", bytesRead);
                    return;
                }
                
                int messageLength = ((lengthBytes[0] & 0xFF) << 24) |
                                   ((lengthBytes[1] & 0xFF) << 16) |
                                   ((lengthBytes[2] & 0xFF) << 8) |
                                   (lengthBytes[3] & 0xFF);
                
                // 读取消息内容（长度不包括类型字节，但包括长度字段本身）
                byte[] messageBytes = new byte[messageLength - 4];
                if (messageLength > 4) {
                    int totalRead = 0;
                    while (totalRead < messageBytes.length) {
                        int read = clientIn.read(messageBytes, totalRead, messageBytes.length - totalRead);
                        if (read == -1) {
                            log.warn("Unexpected end of stream while reading message");
                            return;
                        }
                        totalRead += read;
                    }
                }
                
                log.debug("Received message type: '{}' (0x{}), length: {}", 
                    messageType, Integer.toHexString(messageTypeByte), messageLength);
                
                // 根据消息类型处理
                switch (messageType) {
                    case 'Q': // Simple query
                        // 检查是否需要切换数据库
                        String newDatabase = checkDatabaseSwitch(messageBytes, currentDatabase);
                        if (newDatabase != null && !newDatabase.equals(currentDatabase)) {
                            try {
                                connection = switchDatabase(connection, newDatabase);
                                currentDatabase = newDatabase;
                            } catch (SQLException e) {
                                log.error("Failed to switch database to: {}", newDatabase, e);
                                byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "08006", 
                                    "Failed to switch database: " + e.getMessage());
                                clientOut.write(errorPacket);
                                clientOut.flush();
                                
                                byte[] readyPacket = PostgreSQLPacket.createReadyForQueryPacket('I');
                                clientOut.write(readyPacket);
                                clientOut.flush();
                                continue;
                            }
                        }
                        handleSimpleQuery(messageBytes, clientOut, connection);
                        break;
                        
                    case 'P': // Parse
                        handleParse(messageBytes, clientOut, connection);
                        break;
                        
                    case 'B': // Bind
                        handleBind(messageBytes, clientOut, connection);
                        break;
                        
                    case 'E': // Execute
                        handleExecute(messageBytes, clientOut, connection);
                        break;
                        
                    case 'D': // Describe
                        handleDescribe(messageBytes, clientOut, connection);
                        break;
                        
                    case 'C': // Close
                        handleClose(messageBytes, clientOut, connection);
                        break;
                        
                    case 'S': // Sync
                        handleSync(clientOut);
                        break;
                        
                    case 'X': // Terminate
                        log.info("Received Terminate message, closing connection");
                        return;
                        
                    default:
                        // 对于其他消息，返回一个错误响应
                        log.debug("Received unknown message type: '{}' (0x{})", messageType, Integer.toHexString(messageTypeByte));
                        byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "0A000", 
                            "Unsupported message type: " + messageType);
                        clientOut.write(errorPacket);
                        clientOut.flush();
                        
                        // 发送ReadyForQuery
                        byte[] readyPacket = PostgreSQLPacket.createReadyForQueryPacket('I');
                        clientOut.write(readyPacket);
                        clientOut.flush();
                        break;
                }
            }
        } catch (IOException e) {
            // 客户端断开连接是正常情况，不需要记录错误日志
            if (!clientSocket.isClosed()) {
                log.debug("Client connection closed: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("Error processing client message", e);
            // 发送错误包给客户端
            try {
                byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "XX000", 
                    "Server Error: " + e.getMessage());
                clientOut.write(errorPacket);
                clientOut.flush();
                
                // 发送ReadyForQuery
                byte[] readyPacket = PostgreSQLPacket.createReadyForQueryPacket('I');
                clientOut.write(readyPacket);
                clientOut.flush();
            } catch (IOException ioException) {
                log.debug("Failed to send error packet to client: {}", ioException.getMessage());
            }
        }
    }
    
    /**
     * 处理简单查询消息
     */
    private void handleSimpleQuery(byte[] messageBytes, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 提取SQL查询（以null结尾）
            int queryLength = messageBytes.length;
            if (queryLength > 0 && messageBytes[queryLength - 1] == 0) {
                queryLength--; // 去掉null终止符
            }
            String sqlQuery = new String(messageBytes, 0, queryLength, "UTF-8").trim();
            log.info("Received SQL query: {}", sqlQuery);
            
            // 处理特殊的客户端命令
            String upperQuery = sqlQuery.toUpperCase();
            
            // Navicat等客户端会发送 set client_encoding to 'UNICODE'
            // 需要转换为 UTF8，因为PostgreSQL JDBC驱动要求UTF8
            if (upperQuery.contains("SET CLIENT_ENCODING") && upperQuery.contains("UNICODE")) {
                log.debug("Converting UNICODE to UTF8 for client_encoding");
                sqlQuery = "SET client_encoding TO 'UTF8'";
            }
            
            // 执行SQL查询 - 直接透传到后端数据库
            Statement stmt = backendConnection.createStatement();
            boolean hasResultSet = stmt.execute(sqlQuery);
            
            if (hasResultSet) {
                // 有结果集，发送结果
                ResultSet rs = stmt.getResultSet();
                sendResultSet(rs, clientOut);
                rs.close();
            } else {
                // 没有结果集（UPDATE/INSERT/DELETE等）
                int updateCount = stmt.getUpdateCount();
                String commandTag;
                
                // 根据SQL类型确定命令标签（复用之前的upperQuery变量）
                if (upperQuery.startsWith("INSERT")) {
                    commandTag = "INSERT 0 " + updateCount;
                } else if (upperQuery.startsWith("UPDATE")) {
                    commandTag = "UPDATE " + updateCount;
                } else if (upperQuery.startsWith("DELETE")) {
                    commandTag = "DELETE " + updateCount;
                } else if (upperQuery.startsWith("CREATE")) {
                    commandTag = "CREATE TABLE";
                } else if (upperQuery.startsWith("DROP")) {
                    commandTag = "DROP TABLE";
                } else if (upperQuery.startsWith("ALTER")) {
                    commandTag = "ALTER TABLE";
                } else if (upperQuery.startsWith("SET")) {
                    commandTag = "SET";
                } else {
                    commandTag = "SELECT " + updateCount;
                }
                
                byte[] commandCompletePacket = PostgreSQLPacket.createCommandCompletePacket(commandTag);
                clientOut.write(commandCompletePacket);
                clientOut.flush();
            }
            
            stmt.close();
            
            // 发送ReadyForQuery包
            byte[] readyForQueryPacket = PostgreSQLPacket.createReadyForQueryPacket('I');
            clientOut.write(readyForQueryPacket);
            clientOut.flush();
            
        } catch (SQLException e) {
            log.error("SQL error executing query", e);
            byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "42000", 
                "SQL Error: " + e.getMessage());
            clientOut.write(errorPacket);
            clientOut.flush();
            
            // 发送ReadyForQuery包
            byte[] readyForQueryPacket = PostgreSQLPacket.createReadyForQueryPacket('I');
            clientOut.write(readyForQueryPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling Simple Query message", e);
            byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "XX000", 
                "Error: " + e.getMessage());
            clientOut.write(errorPacket);
            clientOut.flush();
            
            // 发送ReadyForQuery包
            byte[] readyForQueryPacket = PostgreSQLPacket.createReadyForQueryPacket('I');
            clientOut.write(readyForQueryPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 发送结果集到客户端
     */
    private void sendResultSet(ResultSet rs, OutputStream clientOut) throws SQLException, IOException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        
        // 发送RowDescription消息
        byte[] rowDescPacket = PostgreSQLResultSet.createRowDescriptionPacket(metaData, columnCount);
        clientOut.write(rowDescPacket);
        clientOut.flush();
        
        // 发送DataRow消息
        int rowCount = 0;
        while (rs.next()) {
            byte[] dataRowPacket = PostgreSQLResultSet.createDataRowPacket(rs, columnCount);
            clientOut.write(dataRowPacket);
            clientOut.flush();
            rowCount++;
            log.debug("Sent row {}", rowCount);
        }
        log.debug("Total rows sent: {}", rowCount);
        
        // 发送CommandComplete消息
        byte[] commandCompletePacket = PostgreSQLPacket.createCommandCompletePacket("SELECT " + rowCount);
        clientOut.write(commandCompletePacket);
        clientOut.flush();
    }
    
    /**
     * 处理Describe消息
     */
    private void handleDescribe(byte[] messageBytes, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 发送NoData响应
            byte[] noDataPacket = PostgreSQLPacket.createPacket('n');
            clientOut.write(noDataPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling Describe message", e);
            byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "XX000", 
                "Error: " + e.getMessage());
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理Close消息
     */
    private void handleClose(byte[] messageBytes, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 发送CloseComplete响应
            byte[] closeCompletePacket = PostgreSQLPacket.createPacket('3');
            clientOut.write(closeCompletePacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling Close message", e);
            byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "XX000", 
                "Error: " + e.getMessage());
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理Parse消息
     */
    private void handleParse(byte[] messageBytes, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该处理Parse消息
            // 为简化起见，我们直接返回Parse完成响应
            byte[] parseCompletePacket = PostgreSQLPacket.createPacket('1');
            clientOut.write(parseCompletePacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling Parse message", e);
            byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "XX000", 
                "Error: " + e.getMessage());
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理Bind消息
     */
    private void handleBind(byte[] messageBytes, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该处理Bind消息
            // 为简化起见，我们直接返回Bind完成响应
            byte[] bindCompletePacket = PostgreSQLPacket.createPacket('2');
            clientOut.write(bindCompletePacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling Bind message", e);
            byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "XX000", 
                "Error: " + e.getMessage());
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理Execute消息
     */
    private void handleExecute(byte[] messageBytes, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该处理Execute消息
            // 为简化起见，我们直接返回命令完成包
            byte[] commandCompletePacket = PostgreSQLPacket.createCommandCompletePacket("SELECT 0");
            clientOut.write(commandCompletePacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling Execute message", e);
            byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "XX000", 
                "Error: " + e.getMessage());
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理Sync消息
     */
    private void handleSync(OutputStream clientOut) throws IOException {
        try {
            // 发送ReadyForQuery包
            byte[] readyForQueryPacket = PostgreSQLPacket.createReadyForQueryPacket('I');
            clientOut.write(readyForQueryPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling Sync message", e);
            byte[] errorPacket = PostgreSQLPacket.createErrorResponsePacket("ERROR", "XX000", 
                "Error: " + e.getMessage());
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
}