package com.whosly.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

@Service
public class DatabaseConnectionService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionService.class);

    /**
     * Connect to a database using the provided credentials.
     * 
     * @param url the database URL
     * @param username the database username
     * @param password the database password
     * @return a database connection
     * @throws SQLException if connection fails
     */
    public Connection connectToDatabase(String url, String username, String password) throws SQLException {
        log.info("Connecting to database: {}", url);
        
        try {
            Connection connection = DriverManager.getConnection(url, username, password);
            log.info("Successfully connected to database: {}", url);
            return connection;
        } catch (SQLException e) {
            log.error("Failed to connect to database: {}", url, e);
            throw e;
        }
    }
    
    /**
     * Close a database connection.
     * 
     * @param connection the connection to close
     */
    public void closeConnection(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
                log.info("Database connection closed successfully");
            } catch (SQLException e) {
                log.error("Error closing database connection", e);
            }
        }
    }
}