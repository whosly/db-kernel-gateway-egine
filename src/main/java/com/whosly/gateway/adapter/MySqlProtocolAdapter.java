package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.mysql.MySQLHandshake;
import com.whosly.gateway.adapter.mysql.MySQLPacket;
import com.whosly.gateway.adapter.mysql.MySQLResultSet;
import com.whosly.gateway.parser.SqlParser;
import com.whosly.gateway.parser.DruidSqlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MySQL protocol adapter implementation.
 * This adapter handles MySQL client connections and proxies them to backend databases.
 */
public class MySqlProtocolAdapter extends AbstractProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(MySqlProtocolAdapter.class);
    private static final String PROTOCOL_NAME = "MySQL";
    private static final int DEFAULT_PORT = 3307;
    
    public MySqlProtocolAdapter() {
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
            log.info("Handling new client connection from {}", clientSocket.getRemoteSocketAddress());
            
            // Get input/output streams for client
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();
            
            // First, connect to the backend database to get version information
            String dbUrl = String.format("jdbc:mysql://%s:%d/%s", targetHost, targetPort, targetDatabase);
            String username = targetUsername;
            String password = targetPassword;
            
            log.info("Connecting to backend database: {} with user: {}", dbUrl, username);
            backendConnection = databaseConnectionService.connectToDatabase(dbUrl, username, password);
            log.info("Successfully connected to backend database: {}", dbUrl);
            
            // Perform MySQL handshake with actual database version
            log.debug("Performing MySQL handshake");
            performHandshake(clientIn, clientOut, backendConnection);
            log.debug("MySQL handshake completed");
            
            // Authenticate with backend database
            log.debug("Authenticating client");
            MySQLHandshake.AuthInfo authInfo = authenticateClient(clientIn, clientOut);
            log.debug("Client authentication completed, authInfo: {}", authInfo);
            
            // 检查是否是SSL请求
            if (authInfo != null && authInfo.isSSLRequest()) {
                log.debug("SSL request received, switching to SSL mode");
                // 在实际实现中，这里应该切换到SSL模式
                // 为简化起见，我们直接返回错误
                byte[] errorPacket = MySQLHandshake.createErrorPacket(1045, "28000", "SSL not supported", 1);
                clientOut.write(errorPacket);
                clientOut.flush();
                return;
            }
            
            if (authInfo != null) {
                // Send OK packet to client
                log.debug("Sending OK packet to client");
                byte[] okPacket = MySQLHandshake.createOkPacket(2);
                clientOut.write(okPacket);
                clientOut.flush();
                log.debug("OK packet sent to client");
                
                // Proxy communication between client and backend
                log.info("Starting proxy communication");
                proxyConnection(clientSocket, clientIn, clientOut, backendConnection);
                log.info("Proxy communication ended");
            } else {
                log.warn("Client authentication failed");
                // 发送认证失败错误包
                byte[] errorPacket = MySQLHandshake.createErrorPacket(1045, "28000", "Access denied", 1);
                clientOut.write(errorPacket);
                clientOut.flush();
            }
        } catch (Exception e) {
            log.error("Error handling MySQL client connection from {}", clientSocket.getRemoteSocketAddress(), e);
            try {
                // 尝试发送错误包给客户端
                try {
                    byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Connection Error: " + e.getMessage(), 1);
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
     * Perform the initial MySQL protocol handshake.
     */
    private void performHandshake(InputStream clientIn, OutputStream clientOut, Connection backendConnection) throws IOException {
        // Create and send server greeting packet with actual database version
        byte[] handshakeData = MySQLHandshake.createHandshakePacket(backendConnection);
        byte[] handshakePacket = MySQLPacket.createPacket(handshakeData, 0);
        
        clientOut.write(handshakePacket);
        clientOut.flush();
    }

    /**
     * Authenticate client and get database connection information.
     * 
     * @return AuthInfo with client authentication details or null if authentication failed
     */
    private MySQLHandshake.AuthInfo authenticateClient(InputStream clientIn, OutputStream clientOut) throws IOException {
        // Read client authentication packet
        MySQLPacket.PacketInfo packetInfo = MySQLPacket.readPacket(clientIn);
        byte[] packetData = packetInfo.getPayload();
        
        // Parse authentication packet
        return MySQLHandshake.parseAuthPacket(packetData);
    }

    /**
     * Proxy communication between client and backend database.
     */
    private void proxyConnection(Socket clientSocket, InputStream clientIn, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            while (!clientSocket.isClosed() && !Thread.currentThread().isInterrupted()) {
                try {
                    // 读取客户端发送的数据包
                    MySQLPacket.PacketInfo packetInfo = MySQLPacket.readPacket(clientIn);
                    byte[] packetData = packetInfo.getPayload();
                    int sequenceId = packetInfo.getSequenceId();
                    
                    // 检查包类型
                    if (packetData.length > 0) {
                        int command = packetData[0] & 0xFF;
                        
                        switch (command) {
                            case 0x01: // COM_QUIT
                                log.info("Received COM_QUIT command, closing connection");
                                return;
                                
                            case 0x02: // COM_INIT_DB
                                handleInitDbCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x03: // COM_QUERY
                                // 处理SQL查询命令
                                handleQueryCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x04: // COM_FIELD_LIST
                                handleFieldListCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x05: // COM_CREATE_DB
                                handleCreateDbCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x06: // COM_DROP_DB
                                handleDropDbCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x08: // COM_REFRESH
                                handleRefreshCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x09: // COM_STATISTICS
                                // 返回统计信息
                                handleStatisticsCommand(sequenceId, clientOut);
                                break;
                                
                            case 0x0A: // COM_PROCESS_INFO
                                handleProcessInfoCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x0B: // COM_CONNECT
                                handleConnectCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x0C: // COM_PROCESS_KILL
                                handleProcessKillCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x0D: // COM_DEBUG
                                handleDebugCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x0E: // COM_PING
                                handlePingCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            case 0x11: // COM_CHANGE_USER
                                handleChangeUserCommand(packetData, sequenceId, clientOut, backendConnection);
                                break;
                                
                            default:
                                // 对于其他命令，返回一个OK包
                                log.debug("Received command: 0x{}, returning OK packet", Integer.toHexString(command));
                                byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
                                clientOut.write(okPacket);
                                clientOut.flush();
                                break;
                        }
                    }
                } catch (IOException e) {
                    // 客户端断开连接是正常情况，不需要记录错误日志
                    if (!clientSocket.isClosed()) {
                        log.debug("Client connection closed: {}", e.getMessage());
                    }
                    return;
                } catch (Exception e) {
                    log.error("Error processing client command", e);
                    // 发送错误包给客户端
                    try {
                        byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Server Error: " + e.getMessage(), 1);
                        clientOut.write(errorPacket);
                        clientOut.flush();
                    } catch (IOException ioException) {
                        log.debug("Failed to send error packet to client: {}", ioException.getMessage());
                    }
                    // 继续处理其他命令
                }
            }
        } finally {
            // 确保关闭客户端连接
            try {
                if (!clientSocket.isClosed()) {
                    clientSocket.close();
                }
            } catch (IOException e) {
                log.debug("Error closing client socket: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 处理COM_QUERY命令
     */
    private void handleQueryCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 提取SQL查询（去掉命令字节）
            String sqlQuery = new String(packetData, 1, packetData.length - 1);
            log.info("Received SQL query: {}", sqlQuery);
            
            // 检查是否是特殊查询命令
            String trimmedQuery = sqlQuery.trim();
            if (trimmedQuery.equalsIgnoreCase("SELECT DATABASE()")) {
                // 返回当前数据库名称
                handleSelectDatabaseQuery(sequenceId, clientOut, backendConnection);
            } else if (trimmedQuery.toUpperCase().startsWith("SHOW DATABASES")) {
                // 返回数据库列表
                handleShowDatabasesQuery(sequenceId, clientOut, backendConnection);
            } else if (trimmedQuery.toUpperCase().startsWith("SHOW TABLES")) {
                // 返回表列表
                handleShowTablesQuery(sequenceId, clientOut, backendConnection);
            } else if (trimmedQuery.toUpperCase().startsWith("SHOW VARIABLES")) {
                // 处理SHOW VARIABLES查询
                handleShowVariablesQuery(trimmedQuery, sequenceId, clientOut, backendConnection);
            } else if (trimmedQuery.toUpperCase().startsWith("SHOW")) {
                // 处理其他SHOW查询，直接转发到后端数据库
                executeSqlQuery(sqlQuery, sequenceId, clientOut, backendConnection);
            } else {
                // 执行普通的SQL查询
                executeSqlQuery(sqlQuery, sequenceId, clientOut, backendConnection);
            }
        } catch (Exception e) {
            log.error("Error handling COM_QUERY command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理SELECT DATABASE()查询
     */
    private void handleSelectDatabaseQuery(int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            String currentDatabase = "demo"; // 默认数据库
            try {
                if (backendConnection != null && !backendConnection.isClosed()) {
                    currentDatabase = backendConnection.getCatalog();
                }
            } catch (SQLException e) {
                log.warn("Failed to get current database, using default: demo");
            }
            
            // 发送结果集
            sendSingleValueResultSet(currentDatabase, "DATABASE()", sequenceId, clientOut);
        } catch (Exception e) {
            log.error("Error handling SELECT DATABASE() query", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理SHOW DATABASES查询
     */
    private void handleShowDatabasesQuery(int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 从后端数据库获取真实的数据库列表
            Statement stmt = backendConnection.createStatement();
            ResultSet rs = stmt.executeQuery("SHOW DATABASES");
            
            // 发送结果集
            sendResultSet(rs, sequenceId, clientOut);
            
            rs.close();
            stmt.close();
        } catch (Exception e) {
            log.error("Error handling SHOW DATABASES query", e);
            // 如果无法从后端获取数据库列表，则返回硬编码的列表
            try {
                String[] databases = {"information_schema", "demo", "mysql", "performance_schema", "sys"};
                
                // 发送列数包
                byte[] columnCountPacket = MySQLPacket.createPacket(new byte[]{1}, sequenceId);
                clientOut.write(columnCountPacket);
                clientOut.flush();
                
                // 发送列定义
                sendColumnDefinition("Database", sequenceId + 1, clientOut);
                
                // 发送列定义结束包
                byte[] columnEofPacket = MySQLResultSet.createEofPacket(sequenceId + 2);
                clientOut.write(columnEofPacket);
                clientOut.flush();
                
                // 发送行数据
                int rowSequenceId = sequenceId + 3;
                for (String database : databases) {
                    sendTextRowData(new String[]{database}, rowSequenceId++, clientOut);
                }
                
                // 发送结果集结束包
                byte[] resultEofPacket = MySQLResultSet.createEofPacket(rowSequenceId);
                clientOut.write(resultEofPacket);
                clientOut.flush();
            } catch (Exception fallbackException) {
                log.error("Error sending fallback database list", fallbackException);
                byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + fallbackException.getMessage(), sequenceId + 1);
                clientOut.write(errorPacket);
                clientOut.flush();
            }
        }
    }
    
    /**
     * 处理SHOW TABLES查询
     */
    private void handleShowTablesQuery(int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 获取当前数据库名称
            String currentDatabase = "demo"; // 默认数据库
            try {
                if (backendConnection != null && !backendConnection.isClosed()) {
                    currentDatabase = backendConnection.getCatalog();
                }
            } catch (SQLException ex) {
                log.warn("Failed to get current database, using default: demo");
            }
            
            // 从后端数据库获取指定数据库的表列表
            Statement stmt = backendConnection.createStatement();
            String query = "SHOW TABLES FROM `" + currentDatabase + "`";
            ResultSet rs = stmt.executeQuery(query);
            
            // 发送结果集
            sendResultSet(rs, sequenceId, clientOut);
            
            rs.close();
            stmt.close();
        } catch (Exception e) {
            log.error("Error handling SHOW TABLES query", e);
            // 如果无法从后端获取表列表，则返回硬编码的列表
            try {
                // 获取当前数据库名称
                String currentDatabase = "demo"; // 默认数据库
                try {
                    if (backendConnection != null && !backendConnection.isClosed()) {
                        currentDatabase = backendConnection.getCatalog();
                    }
                } catch (SQLException ex) {
                    log.warn("Failed to get current database, using default: demo");
                }
                
                // 对于不同的系统数据库，返回不同的表列表
                String[] tables;
                if ("information_schema".equals(currentDatabase)) {
                    tables = new String[]{"COLUMNS", "TABLES", "SCHEMATA", "ROUTINES", "PARAMETERS", "ENGINES", "VARIABLES"};
                } else if ("mysql".equals(currentDatabase)) {
                    tables = new String[]{"user", "db", "tables_priv", "columns_priv", "procs_priv"};
                } else if ("performance_schema".equals(currentDatabase)) {
                    tables = new String[]{"accounts", "hosts", "threads", "events_waits_current", "events_waits_history"};
                } else if ("sys".equals(currentDatabase)) {
                    tables = new String[]{"sys_config", "statements_with_runtimes_in_95th_percentile"};
                } else {
                    tables = new String[]{"a", "b", "c"}; // 默认示例表名
                }
                
                // 发送列数包
                byte[] columnCountPacket = MySQLPacket.createPacket(new byte[]{1}, sequenceId);
                clientOut.write(columnCountPacket);
                clientOut.flush();
                
                // 发送列定义 (使用正确的列名格式)
                sendColumnDefinition("Tables_in_" + currentDatabase, sequenceId + 1, clientOut);
                
                // 发送列定义结束包
                byte[] columnEofPacket = MySQLResultSet.createEofPacket(sequenceId + 2);
                clientOut.write(columnEofPacket);
                clientOut.flush();
                
                // 发送行数据
                int rowSequenceId = sequenceId + 3;
                for (String table : tables) {
                    sendTextRowData(new String[]{table}, rowSequenceId++, clientOut);
                }
                
                // 发送结果集结束包
                byte[] resultEofPacket = MySQLResultSet.createEofPacket(rowSequenceId);
                clientOut.write(resultEofPacket);
                clientOut.flush();
            } catch (Exception fallbackException) {
                log.error("Error sending fallback table list", fallbackException);
                byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + fallbackException.getMessage(), sequenceId + 1);
                clientOut.write(errorPacket);
                clientOut.flush();
            }
        }
    }
    
    /**
     * 执行普通的SQL查询
     */
    private void executeSqlQuery(String sqlQuery, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 处理多语句查询
            String[] statements = sqlQuery.split(";");
            int currentSequenceId = sequenceId;
            
            for (int i = 0; i < statements.length; i++) {
                String statement = statements[i].trim();
                if (statement.isEmpty()) {
                    continue;
                }
                
                Statement stmt = backendConnection.createStatement();
                boolean hasResultSet = stmt.execute(statement);
                
                if (hasResultSet) {
                    ResultSet rs = stmt.getResultSet();
                    sendResultSet(rs, currentSequenceId, clientOut);
                    rs.close();
                    currentSequenceId += 10; // 为下一个结果集留出空间
                } else {
                    // 对于更新语句，发送OK包
                    int updateCount = stmt.getUpdateCount();
                    byte[] okPacket = MySQLHandshake.createOkPacket(currentSequenceId + 1);
                    clientOut.write(okPacket);
                    clientOut.flush();
                    currentSequenceId += 2; // 为下一个OK包留出空间
                }
                
                stmt.close();
            }
        } catch (SQLException e) {
            log.error("Error executing SQL query: {}", sqlQuery, e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "SQL Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 发送单值结果集
     */
    private void sendSingleValueResultSet(String value, String columnName, int sequenceId, OutputStream clientOut) throws IOException {
        try {
            // 发送列数包
            byte[] columnCountPacket = MySQLPacket.createPacket(new byte[]{1}, sequenceId);
            clientOut.write(columnCountPacket);
            clientOut.flush();
            
            // 发送列定义包
            sendColumnDefinition(columnName, sequenceId + 1, clientOut);
            
            // 发送行数据
            sendTextRowData(new String[]{value}, sequenceId + 2, clientOut);
            
            // 发送EOF包
            byte[] eofPacket = MySQLResultSet.createEofPacket(sequenceId + 3);
            clientOut.write(eofPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error sending single value result set", e);
            throw new IOException("Failed to send result set", e);
        }
    }
    
    /**
     * 发送列定义
     */
    private void sendColumnDefinition(String columnName, int sequenceId, OutputStream clientOut) throws IOException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            
            // 目录名称
            baos.write(MySQLPacket.writeLengthEncodedString("def"));
            
            // 模式名称（数据库名）
            baos.write(MySQLPacket.writeLengthEncodedString(""));
            
            // 表名称
            baos.write(MySQLPacket.writeLengthEncodedString(""));
            
            // 原始表名称
            baos.write(MySQLPacket.writeLengthEncodedString(""));
            
            // 列名称
            baos.write(MySQLPacket.writeLengthEncodedString(columnName));
            
            // 原始列名称
            baos.write(MySQLPacket.writeLengthEncodedString(columnName));
            
            // 填充字段
            baos.write(MySQLPacket.writeLengthEncodedInteger(0x0C));
            
            // 字符集
            baos.write(0x21); // utf8_general_ci
            baos.write(0x00);
            
            // 列长度
            baos.write(0xFF);
            baos.write(0xFF);
            baos.write(0xFF);
            baos.write(0xFF);
            
            // 列类型
            baos.write(0x0F); // MYSQL_TYPE_STRING
            
            // 标志
            baos.write(0x00);
            baos.write(0x00);
            
            // 小数位数
            baos.write(0x00);
            
            // 填充字段
            baos.write(0x00);
            baos.write(0x00);
            
            byte[] columnDefPacket = MySQLPacket.createPacket(baos.toByteArray(), sequenceId);
            clientOut.write(columnDefPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error sending column definition", e);
            throw new IOException("Failed to send column definition", e);
        }
    }
    
    /**
     * 发送文本行数据
     */
    private void sendTextRowData(String[] values, int sequenceId, OutputStream clientOut) throws IOException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            
            // 为每个值写入长度编码的字符串
            for (String value : values) {
                if (value == null) {
                    baos.write(0xFB); // NULL值
                } else {
                    baos.write(MySQLPacket.writeLengthEncodedString(value));
                }
            }
            
            byte[] rowDataPacket = MySQLPacket.createPacket(baos.toByteArray(), sequenceId);
            clientOut.write(rowDataPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error sending row data", e);
            throw new IOException("Failed to send row data", e);
        }
    }
    
    /**
     * 发送结果集
     */
    private void sendResultSet(ResultSet rs, int sequenceId, OutputStream clientOut) throws IOException {
        try {
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            
            // 发送列数包
            byte[] columnCountPayload = MySQLPacket.writeLengthEncodedInteger(columnCount);
            byte[] columnCountPacket = MySQLPacket.createPacket(columnCountPayload, sequenceId);
            clientOut.write(columnCountPacket);
            clientOut.flush();
            
            // 发送列定义包
            for (int i = 1; i <= columnCount; i++) {
                byte[] columnDefPacket = MySQLResultSet.createColumnDefinitionPacket(metaData, i, sequenceId + i);
                clientOut.write(columnDefPacket);
                clientOut.flush();
            }
            
            // 发送列定义结束包
            byte[] columnEofPacket = MySQLResultSet.createEofPacket(sequenceId + columnCount + 1);
            clientOut.write(columnEofPacket);
            clientOut.flush();
            
            // 发送行数据
            int rowSequenceId = sequenceId + columnCount + 2;
            while (rs.next()) {
                byte[] rowDataPacket = MySQLResultSet.createRowDataPacket(rs, metaData, columnCount, rowSequenceId++);
                clientOut.write(rowDataPacket);
                clientOut.flush();
            }
            
            // 发送结果集结束包
            byte[] resultEofPacket = MySQLResultSet.createEofPacket(rowSequenceId);
            clientOut.write(resultEofPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error sending result set", e);
            throw new IOException("Failed to send result set", e);
        }
    }
    
    /**
     * 处理COM_INIT_DB命令
     */
    private void handleInitDbCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 提取数据库名称（去掉命令字节）
            String databaseName = new String(packetData, 1, packetData.length - 1);
            log.info("Changing to database: {}", databaseName);
            
            // 实际切换到指定的数据库
            if (backendConnection != null && !backendConnection.isClosed()) {
                backendConnection.setCatalog(databaseName);
            }
            
            // 返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_INIT_DB command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_FIELD_LIST命令
     */
    private void handleFieldListCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该返回表的字段列表
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_FIELD_LIST command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_CREATE_DB命令
     */
    private void handleCreateDbCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 提取数据库名称（去掉命令字节）
            String databaseName = new String(packetData, 1, packetData.length - 1);
            log.info("Creating database: {}", databaseName);
            
            // 在实际实现中，这里应该创建数据库
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_CREATE_DB command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_DROP_DB命令
     */
    private void handleDropDbCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 提取数据库名称（去掉命令字节）
            String databaseName = new String(packetData, 1, packetData.length - 1);
            log.info("Dropping database: {}", databaseName);
            
            // 在实际实现中，这里应该删除数据库
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_DROP_DB command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_REFRESH命令
     */
    private void handleRefreshCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该刷新服务器缓存
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_REFRESH command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_STATISTICS命令
     */
    private void handleStatisticsCommand(int sequenceId, OutputStream clientOut) throws IOException {
        try {
            // 在实际实现中，这里应该返回服务器统计信息
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_STATISTICS command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_PROCESS_INFO命令
     */
    private void handleProcessInfoCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该返回进程信息
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_PROCESS_INFO command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_CONNECT命令
     */
    private void handleConnectCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该处理连接命令
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_CONNECT command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_PROCESS_KILL命令
     */
    private void handleProcessKillCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该杀死指定的进程
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_PROCESS_KILL command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_DEBUG命令
     */
    private void handleDebugCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该输出调试信息
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_DEBUG command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_PING命令
     */
    private void handlePingCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            log.debug("Received COM_PING command");
            // 返回OK包表示服务器存活
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_PING command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理COM_CHANGE_USER命令
     */
    private void handleChangeUserCommand(byte[] packetData, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 在实际实现中，这里应该处理用户切换
            // 为简化起见，我们直接返回OK包
            byte[] okPacket = MySQLHandshake.createOkPacket(sequenceId + 1);
            clientOut.write(okPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error handling COM_CHANGE_USER command", e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 处理SHOW VARIABLES查询
     */
    private void handleShowVariablesQuery(String query, int sequenceId, OutputStream clientOut, Connection backendConnection) throws IOException {
        try {
            // 解析变量名
            String variableName = "";
            if (query.toUpperCase().contains("LIKE")) {
                int likeIndex = query.toUpperCase().indexOf("LIKE");
                String likeClause = query.substring(likeIndex + 5).trim();
                // 移除引号
                if (likeClause.startsWith("'") && likeClause.endsWith("'")) {
                    variableName = likeClause.substring(1, likeClause.length() - 1);
                } else if (likeClause.startsWith("\"") && likeClause.endsWith("\"")) {
                    variableName = likeClause.substring(1, likeClause.length() - 1);
                }
            }
            
            // 根据变量名返回相应的值
            if (variableName.equalsIgnoreCase("lower_case_%")) {
                // 返回lower_case相关的变量
                sendVariablesResultSet(new String[][] {
                    {"lower_case_file_system", "OFF"},
                    {"lower_case_table_names", "0"}
                }, sequenceId, clientOut);
            } else if (variableName.equalsIgnoreCase("sql_mode")) {
                // 返回sql_mode变量
                sendVariablesResultSet(new String[][] {
                    {"sql_mode", "STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION"}
                }, sequenceId, clientOut);
            } else {
                // 返回空结果集或默认值
                sendVariablesResultSet(new String[][] {
                    {"Variable_name", "Value"}
                }, sequenceId, clientOut);
            }
        } catch (Exception e) {
            log.error("Error handling SHOW VARIABLES query: {}", query, e);
            byte[] errorPacket = MySQLHandshake.createErrorPacket(1001, "HY000", "Error: " + e.getMessage(), sequenceId + 1);
            clientOut.write(errorPacket);
            clientOut.flush();
        }
    }
    
    /**
     * 发送变量结果集
     */
    private void sendVariablesResultSet(String[][] variables, int sequenceId, OutputStream clientOut) throws IOException {
        try {
            // 发送列数包（2列：Variable_name, Value）
            byte[] columnCountPacket = MySQLPacket.createPacket(new byte[]{2}, sequenceId);
            clientOut.write(columnCountPacket);
            clientOut.flush();
            
            // 发送列定义包
            sendColumnDefinition("Variable_name", sequenceId + 1, clientOut);
            sendColumnDefinition("Value", sequenceId + 2, clientOut);
            
            // 发送列定义结束包
            byte[] columnEofPacket = MySQLResultSet.createEofPacket(sequenceId + 3);
            clientOut.write(columnEofPacket);
            clientOut.flush();
            
            // 发送行数据
            int rowSequenceId = sequenceId + 4;
            for (int i = 0; i < variables.length; i++) {
                if (variables[i].length >= 2) {
                    sendTextRowData(variables[i], rowSequenceId++, clientOut);
                }
            }
            
            // 发送结果集结束包
            byte[] resultEofPacket = MySQLResultSet.createEofPacket(rowSequenceId);
            clientOut.write(resultEofPacket);
            clientOut.flush();
        } catch (Exception e) {
            log.error("Error sending variables result set", e);
            throw new IOException("Failed to send variables result set", e);
        }
    }
}