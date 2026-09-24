package com.whosly.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Legacy JDBC helper that opens connections via {@link DriverManager}.
 *
 * <p><b>Not part of the wire-proxy data plane.</b> Transparent MySQL/PG forwarding
 * uses {@code BackendProvider} + sockets ({@code AbstractProtocolAdapter}), not
 * this service. Kept only for possible management-side experiments; do not wire
 * it into session relay / DuplexRelay paths.</p>
 *
 * @deprecated Prefer the protocol adapter + {@code BackendProvider}. Removal
 *     candidates once no callers remain outside the adapter field placeholder.
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@Deprecated(since = "1.0.0", forRemoval = false)
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
     * @deprecated Not used by the wire path; see class javadoc.
     */
    @Deprecated(since = "1.0.0", forRemoval = false)
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
     * @deprecated Not used by the wire path; see class javadoc.
     */
    @Deprecated(since = "1.0.0", forRemoval = false)
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
