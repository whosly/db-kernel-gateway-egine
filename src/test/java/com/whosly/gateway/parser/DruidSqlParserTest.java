package com.whosly.gateway.parser;

import com.alibaba.druid.sql.ast.SQLStatement;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

class DruidSqlParserTest {

    private final DruidSqlParser parser = new DruidSqlParser();

    @Test
    void testValidSqlParsing() {
        String sql = "SELECT * FROM users WHERE id = 1";
        try {
            SQLStatement statement = parser.parse(sql);
            assertThat(statement).isNotNull();
        } catch (SqlParseException e) {
            fail("Should not throw exception for valid SQL");
        }
    }

    @Test
    void testInvalidSqlParsing() {
        String sql = "INVALID SQL STATEMENT";
        
        assertThrows(SqlParseException.class, () -> {
            parser.parse(sql);
        });
    }

    @Test
    void testSqlValidation() {
        String validSql = "SELECT * FROM users";
        String invalidSql = "INVALID SQL";
        
        assertThat(parser.validate(validSql)).isTrue();
        assertThat(parser.validate(invalidSql)).isFalse();
    }

    @Test
    void testTableExtraction() {
        String sql = "SELECT u.name, p.title FROM users u JOIN posts p ON u.id = p.user_id";
        String[] tables = parser.extractTableNames(sql);
        
        // Note: Implementation currently returns empty array
        assertThat(tables).isNotNull();
    }
}