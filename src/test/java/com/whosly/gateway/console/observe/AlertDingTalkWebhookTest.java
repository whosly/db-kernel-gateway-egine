package com.whosly.gateway.console.observe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.whosly.gateway.console.persist.AlertChannelStore;
import com.whosly.gateway.console.persist.ConsoleAuditStore;
import com.whosly.gateway.console.security.ConsoleAuditService;
import com.whosly.gateway.console.security.ConsoleSecretCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DingTalk robot webhook formatting + mocked HTTP delivery.
 */
class AlertDingTalkWebhookTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AlertNotificationService service;
    private HttpServer httpServer;
    private final CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> paths = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicInteger dingErrcode = new AtomicInteger(0);

    @BeforeEach
    void setUp() throws IOException {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:alert-dingtalk-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        AlertChannelStore store = new AlertChannelStore(jdbc);
        ConsoleAuditService audit = new ConsoleAuditService(new ConsoleAuditStore(jdbc));
        @SuppressWarnings("unchecked")
        ObjectProvider<ConsoleAuditService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(audit);

        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/robot/send", exchange -> {
            byte[] buf = exchange.getRequestBody().readAllBytes();
            bodies.add(new String(buf, StandardCharsets.UTF_8));
            paths.add(exchange.getRequestURI().getPath());
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            int err = dingErrcode.get();
            byte[] ok = ("{\"errcode\":" + err + ",\"errmsg\":\""
                    + (err == 0 ? "ok" : "sign not match") + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        httpServer.start();

        service = new AlertNotificationService(
                store,
                ConsoleSecretCipher.fromBase64MasterKey(null),
                mapper,
                provider,
                new AlertWebhookSender(mapper),
                new AlertEmailSender((session, message) -> {
                }));
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void formatBodyBuildsDingTalkMarkdown() throws Exception {
        Map<String, Object> alert = sampleAlert("CRITICAL");
        Map<String, Object> canonical = AlertWebhookSender.buildPayload(alert, "alert.fired");
        Map<String, Object> cfg = Map.of(
                "format", "dingtalk_markdown",
                "atMobiles", List.of("13800000000"),
                "isAtAll", false);
        Map<String, Object> body = AlertWebhookSender.formatBody(
                AlertWebhookSender.FORMAT_DINGTALK_MARKDOWN, canonical, cfg);
        assertThat(body.get("msgtype")).isEqualTo("markdown");
        @SuppressWarnings("unchecked")
        Map<String, Object> md = (Map<String, Object>) body.get("markdown");
        assertThat(md.get("title")).asString().contains("CRITICAL");
        assertThat(md.get("text")).asString()
                .contains("### ")
                .contains("deny_count")
                .contains("alert.fired")
                .contains("拒绝偏高");
        @SuppressWarnings("unchecked")
        Map<String, Object> at = (Map<String, Object>) body.get("at");
        assertThat(at.get("atMobiles")).isEqualTo(List.of("13800000000"));
        assertThat(at.get("isAtAll")).isEqualTo(false);
    }

    @Test
    void formatBodyBuildsDingTalkText() {
        Map<String, Object> canonical = AlertWebhookSender.buildPayload(sampleAlert("WARN"), "alert.test");
        Map<String, Object> body = AlertWebhookSender.formatBody(
                AlertWebhookSender.FORMAT_DINGTALK_TEXT, canonical, Map.of());
        assertThat(body.get("msgtype")).isEqualTo("text");
        @SuppressWarnings("unchecked")
        Map<String, Object> text = (Map<String, Object>) body.get("text");
        assertThat(text.get("content")).asString()
                .contains("[WARN]")
                .contains("事件: alert.test")
                .contains("deny_count");
    }

    @Test
    void resolveFormatAutoDetectsDingTalkHost() {
        URI uri = URI.create("https://oapi.dingtalk.com/robot/send?access_token=abc");
        assertThat(AlertWebhookSender.resolveFormat(Map.of(), uri))
                .isEqualTo(AlertWebhookSender.FORMAT_DINGTALK_MARKDOWN);
        assertThat(AlertWebhookSender.resolveFormat(Map.of("format", "generic"), uri))
                .isEqualTo(AlertWebhookSender.FORMAT_GENERIC);
        assertThat(AlertWebhookSender.resolveFormat(Map.of(), URI.create("https://hooks.example/x")))
                .isEqualTo(AlertWebhookSender.FORMAT_GENERIC);
    }

    @Test
    void normalizeFormatRejectsUnknown() {
        assertThatThrownBy(() -> AlertWebhookSender.normalizeFormat("slack"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format");
    }

    @Test
    void signDingTalkUrlAppendsTimestampAndSign() throws Exception {
        String signed = AlertWebhookSender.signDingTalkUrl(
                "https://oapi.dingtalk.com/robot/send?access_token=tok",
                "SECtestsecret",
                1_700_000_000_000L);
        assertThat(signed).contains("timestamp=1700000000000");
        assertThat(signed).contains("&sign=");
    }

    @Test
    void dingtalkMarkdownChannelPostsRobotJsonViaMockHttp() throws Exception {
        int port = httpServer.getAddress().getPort();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("url", "http://127.0.0.1:" + port + "/robot/send?access_token=lab");
        cfg.put("format", "dingtalk_markdown");
        cfg.put("timeoutMs", 3000);
        cfg.put("isAtAll", true);

        Map<String, Object> created = service.create(new AlertNotificationService.ChannelInput(
                "dingtalk-ops", "WEBHOOK", true, "INFO", 0, cfg));
        assertThat(created.get("type")).isEqualTo("WEBHOOK");
        @SuppressWarnings("unchecked")
        Map<String, Object> viewCfg = (Map<String, Object>) created.get("config");
        assertThat(viewCfg.get("format")).isEqualTo("dingtalk_markdown");
        assertThat(viewCfg.get("secret")).isNull();

        service.dispatchFired(sampleAlert("WARN"), true);
        assertThat(bodies).hasSize(1);
        JsonNode root = mapper.readTree(bodies.get(0));
        assertThat(root.path("msgtype").asText()).isEqualTo("markdown");
        assertThat(root.path("markdown").path("title").asText()).contains("WARN");
        assertThat(root.path("markdown").path("text").asText()).contains("deny_count");
        assertThat(root.path("at").path("isAtAll").asBoolean()).isTrue();
        // must NOT be the generic envelope
        assertThat(root.has("event")).isFalse();
        assertThat(root.has("alert")).isFalse();

        Map<String, Object> test = service.testChannel(String.valueOf(created.get("id")));
        assertThat(test.get("ok")).isEqualTo(true);
        assertThat(bodies).hasSize(2);
        JsonNode testBody = mapper.readTree(bodies.get(1));
        assertThat(testBody.path("msgtype").asText()).isEqualTo("markdown");
        assertThat(testBody.path("markdown").path("text").asText()).contains("通道自检");
    }

    @Test
    void dingtalkTextChannelPostsTextMsgtype() throws Exception {
        int port = httpServer.getAddress().getPort();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("url", "http://127.0.0.1:" + port + "/robot/send?access_token=lab");
        cfg.put("format", "dingtalk_text");

        service.create(new AlertNotificationService.ChannelInput(
                "ding-text", "WEBHOOK", true, "INFO", 0, cfg));
        service.dispatchFired(sampleAlert("INFO"), true);
        assertThat(bodies).hasSize(1);
        JsonNode root = mapper.readTree(bodies.get(0));
        assertThat(root.path("msgtype").asText()).isEqualTo("text");
        assertThat(root.path("text").path("content").asText()).contains("事件: alert.fired");
    }

    @Test
    void dingtalkBizErrorWithErrcodeFailsDelivery() {
        dingErrcode.set(310000);
        int port = httpServer.getAddress().getPort();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("url", "http://127.0.0.1:" + port + "/robot/send?access_token=lab");
        cfg.put("format", "dingtalk");

        Map<String, Object> created = service.create(new AlertNotificationService.ChannelInput(
                "ding-fail", "WEBHOOK", true, "INFO", 0, cfg));
        Map<String, Object> test = service.testChannel(String.valueOf(created.get("id")));
        assertThat(test.get("ok")).isEqualTo(false);
        assertThat(String.valueOf(test.get("message"))).contains("钉钉业务错误");
    }

    @Test
    void sealedSecretPreservedOnUpdate() {
        int port = httpServer.getAddress().getPort();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("url", "http://127.0.0.1:" + port + "/robot/send?access_token=lab");
        cfg.put("format", "dingtalk_markdown");
        cfg.put("secret", "SEClabsecret");

        Map<String, Object> created = service.create(new AlertNotificationService.ChannelInput(
                "ding-sec", "WEBHOOK", true, "INFO", 0, cfg));
        @SuppressWarnings("unchecked")
        Map<String, Object> viewCfg = (Map<String, Object>) created.get("config");
        assertThat(viewCfg.get("secretConfigured")).isEqualTo(true);
        assertThat(viewCfg.get("secret")).isNull();

        // update without secret → keep previous
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("url", "http://127.0.0.1:" + port + "/robot/send?access_token=lab2");
        upd.put("format", "dingtalk_markdown");
        Map<String, Object> updated = service.update(String.valueOf(created.get("id")),
                new AlertNotificationService.ChannelInput(
                        "ding-sec", "WEBHOOK", true, "INFO", 0, upd));
        @SuppressWarnings("unchecked")
        Map<String, Object> updCfg = (Map<String, Object>) updated.get("config");
        assertThat(updCfg.get("secretConfigured")).isEqualTo(true);
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
