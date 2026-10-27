package com.whosly.gateway.console.observe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Generic HTTP webhook notifier (JDK {@link HttpClient}).
 * POSTs JSON alert payload; optional auth header and custom headers.
 */
public final class AlertWebhookSender {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookSender.class);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public AlertWebhookSender(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    AlertWebhookSender(ObjectMapper mapper, HttpClient httpClient) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public AlertDeliveryResult send(Map<String, Object> config, Map<String, Object> payload) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(payload, "payload");
        String url = str(config.get("url"));
        if (url == null || url.isBlank()) {
            return AlertDeliveryResult.failure("webhook.url 必填", null);
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return AlertDeliveryResult.failure("webhook.url 非法: " + e.getMessage(), null);
        }
        if (uri.getScheme() == null
                || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            return AlertDeliveryResult.failure("webhook.url 须为 http(s)", null);
        }
        String method = str(config.get("method"));
        if (method == null || method.isBlank()) {
            method = "POST";
        }
        method = method.trim().toUpperCase(Locale.ROOT);
        if (!method.equals("POST") && !method.equals("PUT")) {
            return AlertDeliveryResult.failure("webhook.method 仅支持 POST/PUT", null);
        }
        int timeoutMs = asInt(config.get("timeoutMs"), 8000);
        timeoutMs = Math.min(60_000, Math.max(1000, timeoutMs));

        try {
            byte[] body = mapper.writeValueAsBytes(payload);
            HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("User-Agent", "db-kernel-gateway-alert/1.0")
                    .header("Accept", "application/json, text/plain, */*");

            String auth = str(config.get("authHeader"));
            if (auth != null && !auth.isBlank()) {
                b.header("Authorization", auth.trim());
            }
            Object headersObj = config.get("headers");
            if (headersObj instanceof Map<?, ?> headers) {
                for (Map.Entry<?, ?> e : headers.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) {
                        continue;
                    }
                    String hk = e.getKey().toString().trim();
                    String hv = e.getValue().toString();
                    if (hk.isEmpty() || "authorization".equalsIgnoreCase(hk)
                            || "content-type".equalsIgnoreCase(hk)
                            || "content-length".equalsIgnoreCase(hk)) {
                        continue;
                    }
                    b.header(hk, hv);
                }
            }

            HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                return AlertDeliveryResult.success("HTTP " + code, code);
            }
            String snippet = resp.body() == null ? "" : resp.body();
            if (snippet.length() > 200) {
                snippet = snippet.substring(0, 200);
            }
            return AlertDeliveryResult.failure("HTTP " + code + (snippet.isBlank() ? "" : ": " + snippet), code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AlertDeliveryResult.failure("webhook interrupted", null);
        } catch (Exception e) {
            log.debug("Webhook delivery failed: {}", e.toString());
            return AlertDeliveryResult.failure("webhook error: " + e.getMessage(), null);
        }
    }

    /** Build canonical JSON body for webhook / audit. */
    public static Map<String, Object> buildPayload(Map<String, Object> alert, String event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", event != null ? event : "alert.fired");
        body.put("source", "db-kernel-gateway-console");
        body.put("alert", alert);
        body.put("sentAt", java.time.Instant.now().toString());
        return body;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(o.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return def;
    }
}
