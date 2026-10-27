package com.whosly.gateway.console.observe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Generic HTTP webhook notifier (JDK {@link HttpClient}).
 * POSTs JSON alert payload; optional auth header, custom headers, and DingTalk robot presets.
 *
 * <p>Config keys:
 * <ul>
 *   <li>{@code url} — required http(s)</li>
 *   <li>{@code method} — POST (default) or PUT</li>
 *   <li>{@code timeoutMs}</li>
 *   <li>{@code authHeader} — optional Authorization</li>
 *   <li>{@code headers} — optional extra headers map</li>
 *   <li>{@code format} — {@code generic} (default) | {@code dingtalk} / {@code dingtalk_markdown}
 *       | {@code dingtalk_text}. When unset and URL host looks like DingTalk
 *       ({@code oapi.dingtalk.com}), defaults to markdown robot body.</li>
 *   <li>{@code secret} — optional DingTalk custom-robot signing secret ({@code SEC…});
 *       appends {@code timestamp}&amp;{@code sign} query params</li>
 *   <li>{@code atMobiles} — optional list/string for DingTalk {@code at.atMobiles}</li>
 *   <li>{@code isAtAll} — optional boolean for DingTalk {@code at.isAtAll}</li>
 * </ul>
 */
public final class AlertWebhookSender {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookSender.class);

    public static final String FORMAT_GENERIC = "generic";
    public static final String FORMAT_DINGTALK = "dingtalk";
    public static final String FORMAT_DINGTALK_MARKDOWN = "dingtalk_markdown";
    public static final String FORMAT_DINGTALK_TEXT = "dingtalk_text";

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

        String format = resolveFormat(config, uri);
        Map<String, Object> bodyMap;
        try {
            bodyMap = formatBody(format, payload, config);
        } catch (IllegalArgumentException e) {
            return AlertDeliveryResult.failure(e.getMessage(), null);
        }

        try {
            String signedUrl = maybeSignDingTalkUrl(url.trim(), format, config);
            URI finalUri = URI.create(signedUrl);
            byte[] body = mapper.writeValueAsBytes(bodyMap);
            HttpRequest.Builder b = HttpRequest.newBuilder(finalUri)
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
                // DingTalk often returns HTTP 200 with errcode != 0 in JSON body
                String respBody = resp.body() == null ? "" : resp.body();
                if (isDingTalkFormat(format) && looksLikeDingTalkBizError(respBody)) {
                    String snippet = respBody.length() > 200 ? respBody.substring(0, 200) : respBody;
                    return AlertDeliveryResult.failure("钉钉业务错误: " + snippet, code);
                }
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

    /** Build canonical JSON body for webhook / audit (generic format). */
    public static Map<String, Object> buildPayload(Map<String, Object> alert, String event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", event != null ? event : "alert.fired");
        body.put("source", "db-kernel-gateway-console");
        body.put("alert", alert);
        body.put("sentAt", java.time.Instant.now().toString());
        return body;
    }

    /**
     * Resolve effective format: explicit {@code format}, else DingTalk host → markdown, else generic.
     */
    static String resolveFormat(Map<String, Object> config, URI uri) {
        String raw = str(config.get("format"));
        if (raw != null && !raw.isBlank()) {
            return normalizeFormat(raw);
        }
        if (uri != null && looksLikeDingTalkHost(uri.getHost())) {
            return FORMAT_DINGTALK_MARKDOWN;
        }
        return FORMAT_GENERIC;
    }

    static String normalizeFormat(String raw) {
        String f = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (f) {
            case "generic", "json", "default", "raw" -> FORMAT_GENERIC;
            case "dingtalk", "dingtalk_markdown", "dingtalk-markdown", "markdown" -> FORMAT_DINGTALK_MARKDOWN;
            case "dingtalk_text", "dingtalk-text", "text" -> FORMAT_DINGTALK_TEXT;
            default -> throw new IllegalArgumentException(
                    "webhook.format 须为 generic / dingtalk_markdown / dingtalk_text，收到: " + raw);
        };
    }

    static boolean isDingTalkFormat(String format) {
        return FORMAT_DINGTALK_MARKDOWN.equals(format)
                || FORMAT_DINGTALK.equals(format)
                || FORMAT_DINGTALK_TEXT.equals(format);
    }

    static boolean looksLikeDingTalkHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("oapi.dingtalk.com")
                || h.endsWith(".oapi.dingtalk.com")
                || h.contains("dingtalk");
    }

    /**
     * Transform canonical payload into the wire body for the chosen format.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> formatBody(String format, Map<String, Object> canonical, Map<String, Object> config) {
        if (!isDingTalkFormat(format)) {
            return canonical;
        }
        Map<String, Object> alert = canonical.get("alert") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        String event = String.valueOf(canonical.getOrDefault("event", "alert.fired"));
        String source = String.valueOf(canonical.getOrDefault("source", "db-kernel-gateway-console"));
        String sentAt = String.valueOf(canonical.getOrDefault("sentAt", ""));

        String severity = String.valueOf(alert.getOrDefault("severity", "WARN"));
        String name = String.valueOf(alert.getOrDefault("name", "alert"));
        String message = String.valueOf(alert.getOrDefault("message", name));
        String metricKey = String.valueOf(alert.getOrDefault("metricKey", ""));
        Object value = alert.get("value");
        Object threshold = alert.get("thresholdValue");
        String comparator = String.valueOf(alert.getOrDefault("comparator", ""));
        String scope = String.valueOf(alert.getOrDefault("scope", ""));
        Object instanceId = alert.get("instanceId");
        String firedAt = String.valueOf(alert.getOrDefault("firedAt", sentAt));

        String title = "[" + severity + "] " + name;
        if (title.length() > 64) {
            title = title.substring(0, 64);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (FORMAT_DINGTALK_TEXT.equals(format)) {
            body.put("msgtype", "text");
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("content", buildDingTalkPlainText(
                    title, event, message, metricKey, value, comparator, threshold,
                    scope, instanceId, firedAt, source));
            body.put("text", text);
        } else {
            body.put("msgtype", "markdown");
            Map<String, Object> md = new LinkedHashMap<>();
            md.put("title", title);
            md.put("text", buildDingTalkMarkdown(
                    title, event, message, metricKey, value, comparator, threshold,
                    scope, instanceId, firedAt, source, severity));
            body.put("markdown", md);
        }
        Map<String, Object> at = buildDingTalkAt(config);
        if (at != null && !at.isEmpty()) {
            body.put("at", at);
        }
        return body;
    }

    private static String buildDingTalkMarkdown(
            String title,
            String event,
            String message,
            String metricKey,
            Object value,
            String comparator,
            Object threshold,
            String scope,
            Object instanceId,
            String firedAt,
            String source,
            String severity) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(escapeMd(title)).append('\n');
        sb.append('\n');
        sb.append("- **事件**: ").append(escapeMd(event)).append('\n');
        sb.append("- **级别**: ").append(escapeMd(severity)).append('\n');
        sb.append("- **说明**: ").append(escapeMd(message)).append('\n');
        if (metricKey != null && !metricKey.isBlank() && !"null".equals(metricKey)) {
            sb.append("- **指标**: `").append(metricKey).append('`');
            if (value != null) {
                sb.append(' ').append(value);
            }
            if (comparator != null && !comparator.isBlank() && !"null".equals(comparator) && threshold != null) {
                sb.append(' ').append(comparator).append(' ').append(threshold);
            }
            sb.append('\n');
        }
        if (scope != null && !scope.isBlank() && !"null".equals(scope)) {
            sb.append("- **范围**: ").append(escapeMd(scope));
            if (instanceId != null && !String.valueOf(instanceId).isBlank()
                    && !"null".equals(String.valueOf(instanceId))) {
                sb.append(" / ").append(escapeMd(String.valueOf(instanceId)));
            }
            sb.append('\n');
        }
        if (firedAt != null && !firedAt.isBlank() && !"null".equals(firedAt)) {
            sb.append("- **时间**: ").append(escapeMd(firedAt)).append('\n');
        }
        sb.append("- **来源**: ").append(escapeMd(source)).append('\n');
        return sb.toString();
    }

    private static String buildDingTalkPlainText(
            String title,
            String event,
            String message,
            String metricKey,
            Object value,
            String comparator,
            Object threshold,
            String scope,
            Object instanceId,
            String firedAt,
            String source) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append('\n');
        sb.append("事件: ").append(event).append('\n');
        sb.append("说明: ").append(message).append('\n');
        if (metricKey != null && !metricKey.isBlank() && !"null".equals(metricKey)) {
            sb.append("指标: ").append(metricKey);
            if (value != null) {
                sb.append('=').append(value);
            }
            if (comparator != null && !comparator.isBlank() && !"null".equals(comparator) && threshold != null) {
                sb.append(' ').append(comparator).append(' ').append(threshold);
            }
            sb.append('\n');
        }
        if (scope != null && !scope.isBlank() && !"null".equals(scope)) {
            sb.append("范围: ").append(scope);
            if (instanceId != null && !String.valueOf(instanceId).isBlank()
                    && !"null".equals(String.valueOf(instanceId))) {
                sb.append('/').append(instanceId);
            }
            sb.append('\n');
        }
        if (firedAt != null && !firedAt.isBlank() && !"null".equals(firedAt)) {
            sb.append("时间: ").append(firedAt).append('\n');
        }
        sb.append("来源: ").append(source);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildDingTalkAt(Map<String, Object> config) {
        if (config == null) {
            return null;
        }
        Map<String, Object> at = new LinkedHashMap<>();
        Object mobiles = config.get("atMobiles");
        java.util.List<String> list = new java.util.ArrayList<>();
        if (mobiles instanceof java.util.List<?> raw) {
            for (Object o : raw) {
                if (o != null && !o.toString().isBlank()) {
                    list.add(o.toString().trim());
                }
            }
        } else if (mobiles instanceof String s && !s.isBlank()) {
            for (String part : s.split("[,;\\s]+")) {
                if (!part.isBlank()) {
                    list.add(part.trim());
                }
            }
        }
        if (!list.isEmpty()) {
            at.put("atMobiles", list);
        }
        Object isAtAll = config.get("isAtAll");
        if (isAtAll instanceof Boolean b) {
            at.put("isAtAll", b);
        } else if (isAtAll != null) {
            String v = isAtAll.toString().trim().toLowerCase(Locale.ROOT);
            if ("true".equals(v) || "1".equals(v) || "yes".equals(v)) {
                at.put("isAtAll", true);
            } else if ("false".equals(v) || "0".equals(v) || "no".equals(v)) {
                at.put("isAtAll", false);
            }
        }
        return at.isEmpty() ? null : at;
    }

    /**
     * DingTalk custom robot signing: append timestamp &amp; sign when {@code secret} is set.
     */
    static String maybeSignDingTalkUrl(String url, String format, Map<String, Object> config) throws Exception {
        if (!isDingTalkFormat(format)) {
            return url;
        }
        String secret = str(config.get("secret"));
        if (secret == null || secret.isBlank()) {
            return url;
        }
        long timestamp = System.currentTimeMillis();
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
        char sep = url.contains("?") ? '&' : '?';
        return url + sep + "timestamp=" + timestamp + "&sign=" + sign;
    }

    /** Visible for tests: sign with injected timestamp. */
    static String signDingTalkUrl(String url, String secret, long timestamp) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
        char sep = url.contains("?") ? '&' : '?';
        return url + sep + "timestamp=" + timestamp + "&sign=" + sign;
    }

    private static boolean looksLikeDingTalkBizError(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        // {"errcode":0,"errmsg":"ok"} is success; any other errcode is failure
        String trimmed = body.trim();
        if (!trimmed.contains("errcode")) {
            return false;
        }
        // cheap parse without full Jackson dependency on success path extras
        int idx = trimmed.indexOf("\"errcode\"");
        if (idx < 0) {
            idx = trimmed.indexOf("errcode");
        }
        if (idx < 0) {
            return false;
        }
        int colon = trimmed.indexOf(':', idx);
        if (colon < 0) {
            return false;
        }
        int i = colon + 1;
        while (i < trimmed.length() && Character.isWhitespace(trimmed.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < trimmed.length() && (Character.isDigit(trimmed.charAt(i)) || trimmed.charAt(i) == '-')) {
            i++;
        }
        if (start == i) {
            return false;
        }
        try {
            int code = Integer.parseInt(trimmed.substring(start, i));
            return code != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String escapeMd(String s) {
        if (s == null) {
            return "";
        }
        // DingTalk markdown is light; escape pipes and backticks lightly
        return s.replace("\r\n", "\n").replace("\r", "\n");
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
