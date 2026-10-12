package com.whosly.gateway.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAuditDestinationTest {

    @TempDir
    Path tempDir;

    @Test
    void ensureSchemaAndInsertParsesSessionSequence_idempotentOnReplay() throws Exception {
        String url = "jdbc:h2:file:" + tempDir.resolve("audit-sink").toAbsolutePath()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        JdbcAuditDestination dest = new JdbcAuditDestination(url, "sa", "", "gateway_audit_record");
        assertThat(dest.dialect()).isEqualTo(JdbcAuditDestination.Dialect.H2);

        dest.ensureSchema();
        String line1 = "ts=1700000000001 protocol=MySQL session=s-a sequence=0 operation=COM_QUERY statement=select 1";
        String line2 = "ts=1700000000002 protocol=MySQL session=s-a sequence=1 operation=COM_QUERY statement=select 2";
        dest.writeBatch(List.of(line1, line2));
        // at-least-once replay
        dest.writeBatch(List.of(line1, line2));

        assertThat(dest.countRows()).isEqualTo(2L);
        assertThat(dest.rowsInserted()).isEqualTo(2L);
        assertThat(dest.lastShipAt()).isNotNull();
        assertThat(dest.lastError()).isNull();

        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT session_id, record_sequence, payload FROM gateway_audit_record ORDER BY record_sequence")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("session_id")).isEqualTo("s-a");
            assertThat(rs.getLong("record_sequence")).isEqualTo(0);
            assertThat(rs.getString("payload")).contains("statement=select 1");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("session_id")).isEqualTo("s-a");
            assertThat(rs.getLong("record_sequence")).isEqualTo(1);
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void shipperToH2_endToEnd_assertRows() throws Exception {
        Path spoolDir = tempDir.resolve("spool");
        String url = "jdbc:h2:mem:audit_ship_e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        AuditSpool spool = new AuditSpool(new AuditSpoolConfig(
                spoolDir, "audit.spool", AuditDurability.STRICT,
                32, 1L, 4096, 10L, 1024 * 1024, 64 * 1024));
        spool.append(com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent
                .builder("MySQL", "s-e2e", "COM_QUERY", "select from_shipper").build());
        spool.append(com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent
                .builder("PostgreSQL", "s-e2e-2", "Query", "select pg").build());

        JdbcAuditDestination dest = new JdbcAuditDestination(url, "sa", "", "gateway_audit_record");
        dest.ensureSchema();
        Path offsetFile = spoolDir.resolve("audit.spool.offset");
        AuditShipper shipper = new AuditShipper(spool, dest, new AuditShippingOffset(offsetFile), 10, 50);
        shipper.close(); // final shipOnce
        spool.close();

        assertThat(dest.countRows()).isEqualTo(2L);
        assertThat(shipper.totalShipped()).isEqualTo(2L);
        assertThat(shipper.shippingOffset()).isEqualTo(2L);
        assertThat(shipper.lastError()).isNull();

        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT payload FROM gateway_audit_record")) {
            StringBuilder all = new StringBuilder();
            while (rs.next()) {
                all.append(rs.getString(1)).append('\n');
            }
            assertThat(all.toString()).contains("select from_shipper").contains("select pg");
        }
    }

    @Test
    void parseKey_readsSessionAndSequence() {
        JdbcAuditDestination.ParsedKey key = JdbcAuditDestination.parseKey(
                "ts=1 protocol=MySQL session=abc sequence=7 operation=COM_QUERY statement=x");
        assertThat(key.sessionId()).isEqualTo("abc");
        assertThat(key.sequence()).isEqualTo(7L);
    }

    @Test
    void detectDialect() {
        assertThat(JdbcAuditDestination.detectDialect("jdbc:h2:file:./data/x")).isEqualTo(JdbcAuditDestination.Dialect.H2);
        assertThat(JdbcAuditDestination.detectDialect("jdbc:mysql://localhost/db")).isEqualTo(JdbcAuditDestination.Dialect.MYSQL);
        assertThat(JdbcAuditDestination.detectDialect("jdbc:postgresql://localhost/db")).isEqualTo(JdbcAuditDestination.Dialect.POSTGRES);
    }
}
