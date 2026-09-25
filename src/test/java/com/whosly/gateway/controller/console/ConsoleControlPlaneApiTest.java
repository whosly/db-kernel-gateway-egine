package com.whosly.gateway.controller.console;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapterRegistry;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.adapter.protocol.ObservationConfidence;
import com.whosly.gateway.adapter.protocol.ProtocolConnectionState;
import com.whosly.gateway.adapter.protocol.SessionDirtiness;
import com.whosly.gateway.adapter.protocol.SessionSnapshot;
import com.whosly.gateway.config.GatewayCatalogProperties;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.console.GatewayInstanceRegistry;
import com.whosly.gateway.console.InstanceBackendHealthService;
import com.whosly.gateway.console.SupportedDatabaseCatalog;
import com.whosly.gateway.console.observe.MetricsHistorySampler;
import com.whosly.gateway.console.observe.RecentTrafficRing;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import com.whosly.gateway.runtime.GatewayListenerRuntime.ManagedListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsoleControlPlaneApiTest {

    private ConsoleApiController console;
    private ProtocolAdapter stoppedAdapter;
    private ProtocolAdapter runningAdapter;
    private RecentTrafficRing ring;
    private GatewayConfig gatewayConfig;

    @BeforeEach
    void setUp() {
        runningAdapter = mock(ProtocolAdapter.class);
        when(runningAdapter.isRunning()).thenReturn(true);
        when(runningAdapter.getProtocolName()).thenReturn("MySQL");
        when(runningAdapter.getDefaultPort()).thenReturn(33307);
        when(runningAdapter.getActiveSessions()).thenReturn(List.of());
        when(runningAdapter.getActiveSessionSnapshots()).thenReturn(List.of());
        when(runningAdapter.killClientSession(anyString())).thenReturn(false);

        stoppedAdapter = mock(ProtocolAdapter.class);
        when(stoppedAdapter.isRunning()).thenReturn(false);
        when(stoppedAdapter.getProtocolName()).thenReturn("PostgreSQL");
        when(stoppedAdapter.getDefaultPort()).thenReturn(35433);
        when(stoppedAdapter.getActiveSessions()).thenReturn(List.of());
        when(stoppedAdapter.getActiveSessionSnapshots()).thenReturn(List.of());
        when(stoppedAdapter.killClientSession(anyString())).thenReturn(false);

        gatewayConfig = new GatewayConfig();
        ReflectionTestUtils.setField(gatewayConfig, "proxyDbType", "mysql");
        ReflectionTestUtils.setField(gatewayConfig, "proxyPort", 33307);
        ReflectionTestUtils.setField(gatewayConfig, "targetHost", "127.0.0.1");
        ReflectionTestUtils.setField(gatewayConfig, "targetPort", 3306);
        ReflectionTestUtils.setField(gatewayConfig, "targetUsername", "root");
        ReflectionTestUtils.setField(gatewayConfig, "targetPassword", "s3cret-should-not-leak");
        ReflectionTestUtils.setField(gatewayConfig, "targetDatabase", "mysql");
        ReflectionTestUtils.setField(gatewayConfig, "auditEnabled", true);
        ReflectionTestUtils.setField(gatewayConfig, "auditMaskStatements", true);
        ReflectionTestUtils.setField(gatewayConfig, "auditDestination", "spool");
        ReflectionTestUtils.setField(gatewayConfig, "consoleSecretKeyBase64", "");

        GatewayCatalogProperties catalogProps = new GatewayCatalogProperties();
        GatewayCatalogProperties.DatabaseEntry mysql = new GatewayCatalogProperties.DatabaseEntry();
        mysql.setId("mysql");
        mysql.setDisplayName("MySQL");
        mysql.setEnabled(true);
        mysql.setMaturity("ga");
        mysql.setDefaultProxyPort(33307);
        mysql.setDefaultTargetPort(3306);
        catalogProps.setDatabases(List.of(mysql));
        SupportedDatabaseCatalog catalog =
                new SupportedDatabaseCatalog(catalogProps, ProtocolAdapterRegistry.withBuiltIns());

        Map<String, ManagedListener> listeners = new LinkedHashMap<>();
        listeners.put("gw-run", new ManagedListener(
                "gw-run", "运行中", "mysql", "0.0.0.0", 33307, true, true,
                true, "127.0.0.1", 65530, "mysql", "root",
                runningAdapter, new GatewayRuntimeMetrics(), "config"));
        listeners.put("gw-stop", new ManagedListener(
                "gw-stop", "已停止", "postgresql", "0.0.0.0", 35433, true, true,
                true, "127.0.0.1", 5432, "postgres", "pg",
                stoppedAdapter, new GatewayRuntimeMetrics(), "config"));

        GatewayListenerRuntime runtime = new GatewayListenerRuntime(listeners, "gw-run");
        GatewayInstanceRegistry registry = new GatewayInstanceRegistry(runtime);
        ring = new RecentTrafficRing(8, 64);

        InstanceBackendHealthService health =
                new InstanceBackendHealthService(runtime, Optional.empty(), gatewayConfig, 200);

        ReflectionTestUtils.setField(gatewayConfig, "auditSpoolDir", "./audit");
        MetricsHistorySampler sampler = new MetricsHistorySampler(registry, runtime, 5, 20);
        // do not start scheduler in unit test — call sampleNow manually when needed

        console = new ConsoleApiController(
                catalog, registry, runningAdapter, new GatewayRuntimeMetrics(), gatewayConfig,
                null, null, null, health, ring, runtime, null, sampler, null, null, null);
    }

    @Test
    void sessionsEmptyWhenAdapterStoppedOrIdle() {
        Map<String, Object> body = console.listSessions("gw-stop");
        assertThat(body.get("count")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<?> sessions = (List<?>) body.get("sessions");
        assertThat(sessions).isEmpty();
    }

    @Test
    void sessionsMapsSnapshotFieldsWithoutSecrets() {
        SessionSnapshot snap = new SessionSnapshot(
                "MySQL", "conn-1", ProtocolConnectionState.READY,
                ObservationConfidence.CONFIRMED, false,
                Optional.of("alice"), Optional.of("demo"),
                SessionDirtiness.clean(),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:01:00Z"));
        when(runningAdapter.getActiveSessionSnapshots()).thenReturn(List.of(snap));

        Map<String, Object> body = console.listSessions("gw-run");
        assertThat(body.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) body.get("sessions");
        assertThat(sessions.get(0).get("connectionId")).isEqualTo("conn-1");
        assertThat(sessions.get(0).get("clientUser")).isEqualTo("alice");
        assertThat(body.toString()).doesNotContain("s3cret");
    }

    @Test
    void killUnknownSessionReturns404() {
        assertThatThrownBy(() -> console.killSession("gw-run", "missing-id"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(404));
    }

    @Test
    void exportOmitsPasswordFields() {
        List<Map<String, Object>> instances = console.exportInstances();
        assertThat(instances).isNotEmpty();
        String text = instances.toString();
        assertThat(text).doesNotContain("s3cret-should-not-leak");
        assertThat(text).doesNotContain("targetPassword");
        for (Map<String, Object> row : instances) {
            assertThat(row).containsKey("passwordConfigured");
            assertThat(row).doesNotContainKey("password");
            assertThat(row).doesNotContainKey("targetPassword");
        }

        Map<String, Object> cfg = console.exportConfig();
        assertThat(cfg.toString()).doesNotContain("s3cret-should-not-leak");
        @SuppressWarnings("unchecked")
        Map<String, Object> audit = (Map<String, Object>) cfg.get("audit");
        assertThat(audit.get("enabled")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> security = (Map<String, Object>) cfg.get("security");
        assertThat(security).containsKey("consoleSecretKeyConfigured");
    }

    @Test
    void recentRingBoundsAndFiltersByInstance() {
        for (int i = 0; i < 20; i++) {
            ring.record("gw-run", DatabaseTrafficEvent.builder(
                    "MySQL", "s" + i, "COM_QUERY", "select " + i + " from t where p='secret-pw'").build());
        }
        ring.record("other", DatabaseTrafficEvent.builder(
                "MySQL", "x", "COM_QUERY", "select 1").build());

        assertThat(ring.size()).isEqualTo(8);
        Map<String, Object> body = console.recentStatements("gw-run", 50);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) body.get("entries");
        assertThat(entries).hasSizeLessThanOrEqualTo(8);
        assertThat(entries).allSatisfy(e -> assertThat(e.get("instanceId")).isEqualTo("gw-run"));
        assertThat(body.get("note").toString()).contains("内存环");
    }

    @Test
    void healthCheckMapsTimeoutOrUnreachableTarget() {
        // target port 65530 from setUp — typically nothing listening → ok=false quickly
        Map<String, Object> body = console.healthCheck("gw-run");
        assertThat(body).containsKeys("ok", "latencyMs", "targetHost", "targetPort", "message", "checkedAt");
        assertThat(body.get("ok")).isEqualTo(false);
        assertThat(body.get("targetPort")).isEqualTo(65530);
        assertThat(((Number) body.get("latencyMs")).longValue()).isLessThan(5000L);
    }

    @Test
    void auditStatusExposesNonSecretFields() {
        Map<String, Object> body = console.auditStatus();
        assertThat(body.get("enabled")).isEqualTo(true);
        assertThat(body.get("destination")).isEqualTo("spool");
        assertThat(body.get("spoolDir")).isEqualTo("./audit");
        assertThat(body.get("maskStatements")).isEqualTo(true);
        assertThat(body.toString()).doesNotContain("s3cret");
        assertThat(body).containsKeys("shipperRunning", "recordsPendingHint", "consoleAuditCount", "help");
    }

    @Test
    void metricsHistoryReturnsOverviewPoints() {
        // trigger one sample via reflection on sampler field
        MetricsHistorySampler sampler = (MetricsHistorySampler) ReflectionTestUtils.getField(console, "metricsHistorySampler");
        assertThat(sampler).isNotNull();
        sampler.sampleNow();
        Map<String, Object> body = console.metricsHistory(null, 50);
        assertThat(body.get("scope")).isEqualTo("overview");
        assertThat(body.get("intervalSeconds")).isEqualTo(5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) body.get("points");
        assertThat(points).isNotEmpty();
        assertThat(points.get(0)).containsKeys("t", "connectionsAccepted", "activeConnections");
    }

    @Test
    void metricsHistoryUnknownInstanceFails() {
        assertThatThrownBy(() -> console.metricsHistory("no-such", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void instanceMetricsIncludesPoolBlock() {
        Map<String, Object> body = console.instanceMetrics("gw-run");
        assertThat(body).containsKey("pool");
        @SuppressWarnings("unchecked")
        Map<String, Object> pool = (Map<String, Object>) body.get("pool");
        assertThat(pool).containsKeys("enabled", "idleCount", "maxIdle");
    }
}
