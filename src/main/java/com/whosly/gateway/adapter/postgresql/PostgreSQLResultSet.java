package com.whosly.gateway.adapter.postgresql;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;

/**
 * PostgreSQL结果集处理类
 * 提供将JDBC结果集转换为PostgreSQL协议消息的功能
 */
public class PostgreSQLResultSet {
    
    /**
     * 创建RowDescription包（描述结果集的列）
     * 
     * @param metaData 结果集元数据
     * @param columnCount 列数
     * @return RowDescription包
     * @throws SQLException SQL异常
     */
    public static byte[] createRowDescriptionPacket(ResultSetMetaData metaData, int columnCount) throws SQLException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // 消息类型：'T' (RowDescription)
        baos.write('T');
        
        // 先构建列描述内容
        ByteArrayOutputStream contentBaos = new ByteArrayOutputStream();
        
        // 列数（2字节）
        contentBaos.write((columnCount >> 8) & 0xFF);
        contentBaos.write(columnCount & 0xFF);
        
        // 为每一列添加描述
        for (int i = 1; i <= columnCount; i++) {
            // 列名
            String columnName = metaData.getColumnName(i);
            byte[] columnNameBytes = columnName.getBytes(StandardCharsets.UTF_8);
            contentBaos.write(columnNameBytes, 0, columnNameBytes.length);
            contentBaos.write(0); // null终止符
            
            // 表OID（4字节）- 使用0表示未知
            writeInt4(contentBaos, 0);
            
            // 列属性编号（2字节）- 使用0表示未知
            writeInt2(contentBaos, 0);
            
            // 数据类型OID（4字节）
            int typeOid = getPostgreSQLTypeOid(metaData.getColumnType(i));
            writeInt4(contentBaos, typeOid);
            
            // 数据类型大小（2字节）
            int typeSize = getPostgreSQLTypeSize(metaData.getColumnType(i));
            writeInt2(contentBaos, typeSize);
            
            // 类型修饰符（4字节）- 使用-1表示未知
            writeInt4(contentBaos, -1);
            
            // 格式代码（2字节）- 0表示文本格式
            writeInt2(contentBaos, 0);
        }
        
        byte[] content = contentBaos.toByteArray();
        
        // 消息长度（长度(4) + 内容长度）
        int messageLength = 4 + content.length;
        writeInt4(baos, messageLength);
        
        // 内容
        baos.write(content, 0, content.length);
        
        return baos.toByteArray();
    }
    
    /**
     * 创建DataRow包（结果集的一行数据）
     * 
     * @param rs 结果集
     * @param columnCount 列数
     * @return DataRow包
     * @throws SQLException SQL异常
     */
    public static byte[] createDataRowPacket(ResultSet rs, int columnCount) throws SQLException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // 消息类型：'D' (DataRow)
        baos.write('D');
        
        // 先构建行数据内容
        ByteArrayOutputStream contentBaos = new ByteArrayOutputStream();
        
        // 列数（2字节）
        writeInt2(contentBaos, columnCount);
        
        // 为每一列添加值
        for (int i = 1; i <= columnCount; i++) {
            String value = rs.getString(i);
            
            if (value == null) {
                // NULL值用-1表示长度
                writeInt4(contentBaos, -1);
            } else {
                byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
                int valueLength = valueBytes.length;
                
                // 值长度（4字节）
                writeInt4(contentBaos, valueLength);
                
                // 值内容
                contentBaos.write(valueBytes, 0, valueBytes.length);
            }
        }
        
        byte[] content = contentBaos.toByteArray();
        
        // 消息长度（长度(4) + 内容长度）
        int messageLength = 4 + content.length;
        writeInt4(baos, messageLength);
        
        // 内容
        baos.write(content, 0, content.length);
        
        return baos.toByteArray();
    }
    
    /**
     * 获取PostgreSQL类型OID
     * 
     * @param jdbcType JDBC类型
     * @return PostgreSQL类型OID
     */
    private static int getPostgreSQLTypeOid(int jdbcType) {
        switch (jdbcType) {
            case Types.BOOLEAN:
                return 16; // bool
            case Types.SMALLINT:
                return 21; // int2
            case Types.INTEGER:
                return 23; // int4
            case Types.BIGINT:
                return 20; // int8
            case Types.REAL:
            case Types.FLOAT:
                return 700; // float4
            case Types.DOUBLE:
                return 701; // float8
            case Types.NUMERIC:
            case Types.DECIMAL:
                return 1700; // numeric
            case Types.CHAR:
                return 1042; // bpchar
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
                return 1043; // varchar
            case Types.DATE:
                return 1082; // date
            case Types.TIME:
                return 1083; // time
            case Types.TIMESTAMP:
                return 1114; // timestamp
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                return 17; // bytea
            default:
                return 25; // text (默认)
        }
    }
    
    /**
     * 获取PostgreSQL类型大小
     * 
     * @param jdbcType JDBC类型
     * @return PostgreSQL类型大小
     */
    private static int getPostgreSQLTypeSize(int jdbcType) {
        switch (jdbcType) {
            case Types.BOOLEAN:
                return 1;
            case Types.SMALLINT:
                return 2;
            case Types.INTEGER:
                return 4;
            case Types.BIGINT:
                return 8;
            case Types.REAL:
            case Types.FLOAT:
                return 4;
            case Types.DOUBLE:
                return 8;
            case Types.NUMERIC:
            case Types.DECIMAL:
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.DATE:
            case Types.TIME:
            case Types.TIMESTAMP:
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            default:
                return -1; // 可变长度
        }
    }
    
    /**
     * 写入2字节整数（大端序）
     */
    private static void writeInt2(ByteArrayOutputStream baos, int value) {
        baos.write((value >> 8) & 0xFF);
        baos.write(value & 0xFF);
    }
    
    /**
     * 写入4字节整数（大端序）
     */
    private static void writeInt4(ByteArrayOutputStream baos, int value) {
        baos.write((value >> 24) & 0xFF);
        baos.write((value >> 16) & 0xFF);
        baos.write((value >> 8) & 0xFF);
        baos.write(value & 0xFF);
    }
}
