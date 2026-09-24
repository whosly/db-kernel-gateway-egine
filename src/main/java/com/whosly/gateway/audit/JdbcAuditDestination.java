package com.whosly.gateway.audit;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** JDBC destination for audit records. */
public final class JdbcAuditDestination implements AuditDestination {

    private final String url;
    private final String username;
    private final String password;
    private final String table;
    private final AtomicLong sequence = new AtomicLong();

    public JdbcAuditDestination(String url, String username, String password, String table) {
        this.url = Objects.requireNonNull(url);
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.table = (table == null || table.isBlank()) ? "gateway_audit_record" : table;
    }

    @Override
    public void writeBatch(List<String> records) throws IOException {
        if (records == null || records.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + table + " (session_id, record_sequence, payload) VALUES (?, ?, ?)";
        try (Connection connection = DriverManager.getConnection(url, username, password);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String record : records) {
                statement.setString(1, "shipped");
                statement.setLong(2, sequence.incrementAndGet());
                statement.setString(3, record);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new IOException("Failed to write audit batch to JDBC destination", e);
        }
    }
}
