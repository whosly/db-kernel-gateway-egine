package com.whosly.gateway.console.observe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.whosly.gateway.console.persist.AlertChannelStore;
import com.whosly.gateway.console.persist.ConsoleAuditStore;
import com.whosly.gateway.console.security.ConsoleAuditService;
import com.whosly.gateway.console.security.ConsoleSecretCipher;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlertNotificationServiceTest {

    private AlertNotificationService service;
    private AlertChannelStore store;
    private HttpServer httpServer;
    private final CopyOnWriteArrayList<String> webhookBodies = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> authSeen = new AtomicReference<>();
    private final List<MimeMessage> mails = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:alert-notify-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        store = new AlertChannelStore(jdbc);
        ConsoleAuditService audit = new ConsoleAuditService(new ConsoleAuditStore(jdbc));
        @SuppressWarnings("unchecked")
        ObjectProvider<ConsoleAuditService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(audit);

        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/hook", exchange -> {
            byte[] buf = exchange.getRequestBody().readAllBytes();
            webhookBodies.add(new String(buf, StandardCharsets.UTF_8));
            authSeen.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        httpServer.start();

        AlertEmailSender email = new AlertEmailSender((session, message) -> mails.add(message));
        service = new AlertNotificationService(
                store,
                ConsoleSecretCipher.fromBase64MasterKey(null),
                new ObjectMapper(),
                provider,
                new AlertWebhookSender(new ObjectMapper()),
                email);
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void webhookCrudAndRisingEdgeDelivery() {
        int port = httpServer.getAddress().getPort();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("url", "http://127.0.0.1:" + port + "/hook");
        cfg.put("authHeader", "Bearer lab-token");
        cfg.put("timeoutMs", 3000);

        Map<String, Object> created = service.create(new AlertNotificationService.ChannelInput(
                "ops-hook", "WEBHOOK", true, "WARN", 60, cfg));
        assertThat(created.get("id")).isNotNull();
        assertThat(created.get("type")).isEqualTo("WEBHOOK");
        @SuppressWarnings("unchecked")
        Map<String, Object> viewCfg = (Map<String, Object>) created.get("config");
        assertThat(viewCfg.get("authHeader")).isNull();
        assertThat(viewCfg.get("authHeaderConfigured")).isEqualTo(true);
        assertThat(viewCfg.get("url")).asString().contains("/hook");

        Map<String, Object> alert = sampleAlert("WARN");
        service.dispatchFired(alert, true);
        assertThat(webhookBodies).hasSize(1);
        assertThat(webhookBodies.get(0)).contains("\"event\":\"alert.fired\"");
        assertThat(authSeen.get()).isEqualTo("Bearer lab-token");

        // sustained within cooldown → no second delivery
        service.dispatchFired(alert, false);
        assertThat(webhookBodies).hasSize(1);

        Map<String, Object> test = service.testChannel(String.valueOf(created.get("id")));
        assertThat(test.get("ok")).isEqualTo(true);
        assertThat(webhookBodies).hasSize(2);
    }

    @Test
    void emailChannelDeliversViaSmtpConfig() throws Exception {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("smtpHost", "127.0.0.1");
        cfg.put("smtpPort", 2525);
        cfg.put("from", "alerts@example.com");
        cfg.put("to", List.of("ops@example.com"));
        cfg.put("username", "u");
        cfg.put("password", "p");
        cfg.put("startTls", false);
        cfg.put("subjectPrefix", "[lab]");

        Map<String, Object> created = service.create(new AlertNotificationService.ChannelInput(
                "ops-mail", "EMAIL", true, "INFO", 0, cfg));
        assertThat(created.get("type")).isEqualTo("EMAIL");
        @SuppressWarnings("unchecked")
        Map<String, Object> viewCfg = (Map<String, Object>) created.get("config");
        assertThat(viewCfg.get("password")).isNull();
        assertThat(viewCfg.get("passwordConfigured")).isEqualTo(true);

        service.dispatchFired(sampleAlert("INFO"), true);
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getSubject()).contains("[lab]");
        assertThat(mails.get(0).getSubject()).contains("INFO");
        assertThat((String) mails.get(0).getContent()).contains("metricKey: deny_count");

        Map<String, Object> test = service.testChannel(String.valueOf(created.get("id")));
        assertThat(test.get("ok")).isEqualTo(true);
        assertThat(mails).hasSize(2);
    }

    @Test
    void minSeverityFilters() {
        int port = httpServer.getAddress().getPort();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("url", "http://127.0.0.1:" + port + "/hook");
        service.create(new AlertNotificationService.ChannelInput(
                "crit-only", "WEBHOOK", true, "CRITICAL", 0, cfg));
        service.dispatchFired(sampleAlert("WARN"), true);
        assertThat(webhookBodies).isEmpty();
        Map<String, Object> crit = sampleAlert("CRITICAL");
        service.dispatchFired(crit, true);
        assertThat(webhookBodies).hasSize(1);
    }

    @Test
    void rejectsInvalidTypeAndMissingUrl() {
        assertThatThrownBy(() -> service.create(new AlertNotificationService.ChannelInput(
                "x", "SMS", true, "INFO", 0, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WEBHOOK");
        assertThatThrownBy(() -> service.create(new AlertNotificationService.ChannelInput(
                "x", "WEBHOOK", true, "INFO", 0, Map.of("url", ""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }

    private static Map<String, Object> sampleAlert(String severity) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("thresholdId", "thr-1");
        alert.put("name", "拒绝偏高");
        alert.put("metricKey", "deny_count");
        alert.put("comparator", "GTE");
        alert.put("thresholdValue", 1);
        alert.put("value", 2);
        alert.put("valueMode", "absolute");
        alert.put("severity", severity);
        alert.put("scope", "global");
        alert.put("firedAt", "2026-10-27T00:00:00Z");
        alert.put("message", "拒绝偏高: deny_count=2 GTE 1");
        return alert;
    }
}
