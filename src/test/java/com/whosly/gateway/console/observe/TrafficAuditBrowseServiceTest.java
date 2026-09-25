package com.whosly.gateway.console.observe;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.audit.AuditDurability;
import com.whosly.gateway.audit.AuditSpool;
import com.whosly.gateway.audit.AuditSpoolConfig;
import com.whosly.gateway.config.GatewayConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrafficAuditBrowseServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void ringSourceWhenAuditDisabled() {
        GatewayConfig cfg = new GatewayConfig();
        ReflectionTestUtils.setField(cfg, "auditEnabled", false);
        ReflectionTestUtils.setField(cfg, "auditMaskStatements", true);
        ReflectionTestUtils.setField(cfg, "auditSpoolDir", tempDir.toString());
        ReflectionTestUtils.setField(cfg, "auditFileName", "audit.spool");
        ReflectionTestUtils.setField(cfg, "auditDestination", "spool");

        RecentTrafficRing ring = new RecentTrafficRing(10, 128);
        ring.record("gw-1", DatabaseTrafficEvent.builder("MySQL", "s1", "COM_QUERY",
                "select 'secret-pass' from dual").observedAt(Instant.parse("2026-01-01T00:00:00Z")).build());

        TrafficAuditBrowseService svc = new TrafficAuditBrowseService(cfg, ring);
        Map<String, Object> body = svc.browse(10, null, "ring", null, null);
        assertThat(body.get("source")).isEqualTo("ring");
        assertThat(body.get("auditEnabled")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) body.get("entries");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("statement").toString()).doesNotContain("secret-pass");
        assertThat(body.get("note").toString()).contains("未启用");
    }

    @Test
    void spoolSourceReadsPersistedRecordsAndRedactsPasswordish() throws Exception {
        Path spoolDir = tempDir.resolve("audit");
        GatewayConfig cfg = new GatewayConfig();
        ReflectionTestUtils.setField(cfg, "auditEnabled", true);
        ReflectionTestUtils.setField(cfg, "auditMaskStatements", true);
        ReflectionTestUtils.setField(cfg, "auditSpoolDir", spoolDir.toString());
        ReflectionTestUtils.setField(cfg, "auditFileName", "audit.spool");
        ReflectionTestUtils.setField(cfg, "auditDestination", "spool");

        AuditSpool spool = new AuditSpool(new AuditSpoolConfig(
                spoolDir, "audit.spool", AuditDurability.STRICT,
                32, 1L, 4096, 10L, 1024 * 1024, 64 * 1024));
        spool.append(DatabaseTrafficEvent.builder("MySQL", "s9", "COM_QUERY",
                "set password=hunter2").build());
        spool.close();

        TrafficAuditBrowseService svc = new TrafficAuditBrowseService(cfg, new RecentTrafficRing(4));
        Map<String, Object> body = svc.browse(20, null, "spool", "MySQL", null);
        assertThat(body.get("source")).isEqualTo("spool");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) body.get("entries");
        assertThat(entries).isNotEmpty();
        String stmt = String.valueOf(entries.get(0).get("statement"));
        assertThat(stmt.toLowerCase()).doesNotContain("hunter2");
        assertThat(body.toString()).doesNotContain("auditJdbcPassword");
    }

    @Test
    void jdbcForcedWithoutUrlReturnsHonestEmpty() {
        GatewayConfig cfg = new GatewayConfig();
        ReflectionTestUtils.setField(cfg, "auditEnabled", true);
        ReflectionTestUtils.setField(cfg, "auditJdbcUrl", "");
        ReflectionTestUtils.setField(cfg, "auditSpoolDir", tempDir.toString());
        ReflectionTestUtils.setField(cfg, "auditFileName", "audit.spool");
        ReflectionTestUtils.setField(cfg, "auditDestination", "jdbc");

        TrafficAuditBrowseService svc = new TrafficAuditBrowseService(cfg, null);
        Map<String, Object> body = svc.browse(10, null, "jdbc", null, null);
        assertThat(body.get("source")).isEqualTo("jdbc");
        assertThat(body.get("count")).isEqualTo(0);
        assertThat(body.get("note").toString()).contains("未配置");
    }
}
