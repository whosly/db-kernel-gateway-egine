package com.whosly.gateway.adapter.mysql;

import java.io.ByteArrayOutputStream;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * MySQL结果集处理类
 */
public class MySQLResultSet {
    
    /**
     * 创建结果集列数定义包
     * 
     * @param metaData 结果集元数据
     * @param columnCount 列数
     * @param sequenceId 序列号
     * @return 列数定义包
     * @throws SQLException SQL异常
     */
    public static byte[] createColumnCountPacket(ResultSetMetaData metaData, int columnCount, int sequenceId) throws SQLException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        try {
            // 列数（长度编码的整数）
            baos.write(MySQLPacket.writeLengthEncodedInteger(columnCount));
            
            return MySQLPacket.createPacket(baos.toByteArray(), sequenceId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create column count packet", e);
        }
    }
    
    /**
     * 创建单个列定义包
     * 
     * @param metaData 结果集元数据
     * @param columnIndex 列索引（从1开始）
     * @param sequenceId 序列号
     * @return 列定义包
     * @throws SQLException SQL异常
     */
    public static byte[] createColumnDefinitionPacket(ResultSetMetaData metaData, int columnIndex, int sequenceId) throws SQLException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        try {
            // 目录名称（长度编码的字符串）
            String catalog = metaData.getCatalogName(columnIndex);
            baos.write(MySQLPacket.writeLengthEncodedString(catalog != null ? catalog : "def"));
            
            // 模式名称（长度编码的字符串）
            String schema = metaData.getSchemaName(columnIndex);
            baos.write(MySQLPacket.writeLengthEncodedString(schema != null ? schema : ""));
            
            // 表名称（长度编码的字符串）
            String table = metaData.getTableName(columnIndex);
            baos.write(MySQLPacket.writeLengthEncodedString(table != null ? table : ""));
            
            // 原始表名称（长度编码的字符串）
            String originalTable = metaData.getTableName(columnIndex);
            baos.write(MySQLPacket.writeLengthEncodedString(originalTable != null ? originalTable : ""));
            
            // 列名称（长度编码的字符串）
            String column = metaData.getColumnName(columnIndex);
            baos.write(MySQLPacket.writeLengthEncodedString(column != null ? column : ("col_" + columnIndex)));
            
            // 原始列名称（长度编码的字符串）
            String originalColumn = metaData.getColumnName(columnIndex);
            baos.write(MySQLPacket.writeLengthEncodedString(originalColumn != null ? originalColumn : ("col_" + columnIndex)));
            
            // 填充字段（长度编码的整数，值为0x0C）
            baos.write(MySQLPacket.writeLengthEncodedInteger(0x0C));
            
            // 字符集（2字节）
            baos.write(0x21); // utf8_general_ci
            baos.write(0x00);
            
            // 列长度（4字节）
            int columnLength = getColumnLength(metaData, columnIndex);
            baos.write(columnLength & 0xFF);
            baos.write((columnLength >> 8) & 0xFF);
            baos.write((columnLength >> 16) & 0xFF);
            baos.write((columnLength >> 24) & 0xFF);
            
            // 列类型（1字节）
            int columnType = getColumnType(metaData, columnIndex);
            baos.write(columnType);
            
            // 标志（2字节）
            int flags = getColumnFlags(metaData, columnIndex);
            baos.write(flags & 0xFF);
            baos.write((flags >> 8) & 0xFF);
            
            // 小数位数（1字节）
            baos.write(0x00);
            
            // 填充字段（2字节）
            baos.write(0x00);
            baos.write(0x00);
            
            return MySQLPacket.createPacket(baos.toByteArray(), sequenceId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create column definition packet", e);
        }
    }
    
    /**
     * 创建行数据包
     * 
     * @param resultSet 结果集
     * @param metaData 结果集元数据
     * @param columnCount 列数
     * @param sequenceId 序列号
     * @return 行数据包
     * @throws SQLException SQL异常
     */
    public static byte[] createRowDataPacket(ResultSet resultSet, ResultSetMetaData metaData, int columnCount, int sequenceId) throws SQLException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        try {
            // 为每一列创建值
            for (int i = 1; i <= columnCount; i++) {
                Object value = resultSet.getObject(i);
                if (value == null) {
                    // NULL值用0xFB表示
                    baos.write(0xFB);
                } else {
                    // 非NULL值用长度编码的字符串表示
                    String stringValue = value.toString();
                    baos.write(MySQLPacket.writeLengthEncodedString(stringValue));
                }
            }
            
            return MySQLPacket.createPacket(baos.toByteArray(), sequenceId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create row data packet", e);
        }
    }
    
    /**
     * 创建EOF包
     * 
     * @param sequenceId 序列号
     * @return EOF包
     */
    public static byte[] createEofPacket(int sequenceId) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        try {
            // 字段数 (0xFE表示EOF包)
            baos.write(0xFE);
            
            // 警告计数
            baos.write(0);
            baos.write(0);
            
            // 状态标志
            baos.write(0x02); // SERVER_STATUS_AUTOCOMMIT
            baos.write(0x00);
            
            return MySQLPacket.createPacket(baos.toByteArray(), sequenceId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create EOF packet", e);
        }
    }
    
    // 辅助方法
    
    private static int getColumnLength(ResultSetMetaData metaData, int columnIndex) throws SQLException {
        int columnType = metaData.getColumnType(columnIndex);
        switch (columnType) {
            case Types.INTEGER:
            case Types.BIGINT:
                return 11;
            case Types.VARCHAR:
            case Types.CHAR:
                return metaData.getColumnDisplaySize(columnIndex);
            case Types.DATE:
                return 10;
            case Types.TIMESTAMP:
                return 19;
            case Types.DECIMAL:
            case Types.NUMERIC:
                return metaData.getPrecision(columnIndex) + 2;
            default:
                return 255;
        }
    }
    
    private static int getColumnType(ResultSetMetaData metaData, int columnIndex) throws SQLException {
        int columnType = metaData.getColumnType(columnIndex);
        switch (columnType) {
            case Types.BIT:
                return 0x10; // MYSQL_TYPE_BIT
            case Types.TINYINT:
                return 0x01; // MYSQL_TYPE_TINY
            case Types.SMALLINT:
                return 0x02; // MYSQL_TYPE_SHORT
            case Types.INTEGER:
                return 0x03; // MYSQL_TYPE_LONG
            case Types.BIGINT:
                return 0x08; // MYSQL_TYPE_LONGLONG
            case Types.FLOAT:
                return 0x04; // MYSQL_TYPE_FLOAT
            case Types.DOUBLE:
                return 0x05; // MYSQL_TYPE_DOUBLE
            case Types.DECIMAL:
            case Types.NUMERIC:
                return 0x00; // MYSQL_TYPE_DECIMAL
            case Types.DATE:
                return 0x0a; // MYSQL_TYPE_DATE
            case Types.TIME:
                return 0x0b; // MYSQL_TYPE_TIME
            case Types.TIMESTAMP:
                return 0x0c; // MYSQL_TYPE_TIMESTAMP
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
                return 0x0f; // MYSQL_TYPE_VARCHAR
            case Types.CHAR:
                return 0x0f; // MYSQL_TYPE_VARCHAR
            case Types.BLOB:
            case Types.LONGVARBINARY:
                return 0xfc; // MYSQL_TYPE_BLOB
            default:
                return 0x0f; // MYSQL_TYPE_VARCHAR
        }
    }
    
    private static int getColumnFlags(ResultSetMetaData metaData, int columnIndex) throws SQLException {
        int flags = 0;
        
        if (metaData.isNullable(columnIndex) == ResultSetMetaData.columnNoNulls) {
            flags |= 0x0001; // NOT_NULL_FLAG
        }
        
        if (metaData.isAutoIncrement(columnIndex)) {
            flags |= 0x0200; // AUTO_INCREMENT_FLAG
        }
        
        if (metaData.isSigned(columnIndex)) {
            flags |= 0x0010; // SIGNED_FLAG
        }
        
        return flags;
    }
}