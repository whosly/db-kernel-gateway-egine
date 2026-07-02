package com.whosly.gateway;

import com.whosly.gateway.parser.DruidSqlParser;
import com.whosly.gateway.parser.SqlParser;
import com.whosly.gateway.parser.SqlParseException;

/**
 * Simple demo class to test the SQL parser functionality.
 */
public class ParserDemo {

    public static void main(String[] args) {
        SqlParser parser = new DruidSqlParser();
        
        // Test valid SQL
        String validSql = "SELECT * FROM users WHERE id = 1";
        System.out.println("Testing valid SQL: " + validSql);
        
        try {
            boolean isValid = parser.validate(validSql);
            System.out.println("SQL is valid: " + isValid);
        } catch (Exception e) {
            System.err.println("Error validating SQL: " + e.getMessage());
        }
        
        // Test invalid SQL
        String invalidSql = "INVALID SQL STATEMENT";
        System.out.println("\nTesting invalid SQL: " + invalidSql);
        
        try {
            boolean isValid = parser.validate(invalidSql);
            System.out.println("SQL is valid: " + isValid);
        } catch (Exception e) {
            System.err.println("Error validating SQL: " + e.getMessage());
        }
        
        System.out.println("\nDemo completed.");
    }
}