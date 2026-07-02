package com.whosly.gateway.parser;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.parser.ParserException;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.alibaba.druid.util.JdbcConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Implementation of SqlParser using Alibaba Druid.
 */
@Component
public class DruidSqlParser implements SqlParser {

    private static final Logger log = LoggerFactory.getLogger(DruidSqlParser.class);

    @Override
    public SQLStatement parse(String sql) throws SqlParseException {
        try {
            SQLStatementParser parser = SQLParserUtils.createSQLStatementParser(sql, JdbcConstants.MYSQL);
            return parser.parseStatement();
        } catch (ParserException e) {
            log.error("Failed to parse SQL: {}", sql, e);
            throw new SqlParseException("Failed to parse SQL statement", e);
        }
    }

    @Override
    public boolean validate(String sql) {
        try {
            parse(sql);
            return true;
        } catch (SqlParseException e) {
            return false;
        }
    }

    @Override
    public String[] extractTableNames(String sql) {
        try {
            List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
            Set<String> tableNames = new HashSet<>();
            
            for (SQLStatement statement : statements) {
                // Extract table names from the statement
                // This is a simplified implementation
                String statementStr = statement.toString();
                // In a full implementation, we would traverse the AST to extract table names
                // For now, we'll just return an empty array
            }
            
            return tableNames.toArray(new String[0]);
        } catch (Exception e) {
            log.warn("Failed to extract table names from SQL: {}", sql, e);
            return new String[0];
        }
    }

    @Override
    public String translate(String sql, SqlDialect targetDialect) {
        // In a full implementation, this would translate SQL between dialects
        // For now, we'll just return the original SQL
        log.debug("Translating SQL to dialect: {}", targetDialect);
        return sql;
    }
}