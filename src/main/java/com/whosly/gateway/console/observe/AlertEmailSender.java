package com.whosly.gateway.console.observe;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * SMTP email notifier using Jakarta Mail {@link Session} built from channel config
 * (not Spring {@code spring.mail.*} — channels are console-managed).
 */
public final class AlertEmailSender {

    private static final Logger log = LoggerFactory.getLogger(AlertEmailSender.class);

    @FunctionalInterface
    public interface TransportSender {
        void send(Session session, MimeMessage message) throws Exception;
    }

    private final TransportSender transportSender;

    public AlertEmailSender() {
        this((session, message) -> {
            Transport.send(message);
        });
    }

    AlertEmailSender(TransportSender transportSender) {
        this.transportSender = Objects.requireNonNull(transportSender, "transportSender");
    }

    public AlertDeliveryResult send(Map<String, Object> config, Map<String, Object> alert, String event) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(alert, "alert");
        String host = str(config.get("smtpHost"));
        if (host == null || host.isBlank()) {
            return AlertDeliveryResult.failure("email.smtpHost 必填", null);
        }
        int port = asInt(config.get("smtpPort"), 587);
        if (port <= 0 || port > 65535) {
            return AlertDeliveryResult.failure("email.smtpPort 非法", null);
        }
        String from = str(config.get("from"));
        if (from == null || from.isBlank()) {
            return AlertDeliveryResult.failure("email.from 必填", null);
        }
        List<String> to = parseRecipients(config.get("to"));
        if (to.isEmpty()) {
            return AlertDeliveryResult.failure("email.to 至少一个收件人", null);
        }
        boolean startTls = asBool(config.get("startTls"), true);
        boolean ssl = asBool(config.get("ssl"), false);
        String username = str(config.get("username"));
        String password = str(config.get("password"));

        Properties props = new Properties();
        props.put("mail.smtp.host", host.trim());
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.connectiontimeout", "8000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");
        if (ssl) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.port", String.valueOf(port));
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        } else if (startTls) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "false");
        }
        boolean auth = username != null && !username.isBlank();
        props.put("mail.smtp.auth", auth ? "true" : "false");

        Session session;
        if (auth) {
            final String user = username.trim();
            final String pass = password != null ? password : "";
            props.put("mail.smtp.user", user);
            session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(user, pass);
                }
            });
        } else {
            session = Session.getInstance(props);
        }
        session.setDebug(false);

        try {
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(from.trim(), false));
            for (String addr : to) {
                msg.addRecipient(Message.RecipientType.TO, new InternetAddress(addr, false));
            }
            String prefix = str(config.get("subjectPrefix"));
            if (prefix == null || prefix.isBlank()) {
                prefix = "[db-gateway-alert]";
            }
            String name = String.valueOf(alert.getOrDefault("name", "alert"));
            String severity = String.valueOf(alert.getOrDefault("severity", "WARN"));
            msg.setSubject(prefix.trim() + " " + severity + " " + name, StandardCharsets.UTF_8.name());
            msg.setText(buildBody(alert, event), StandardCharsets.UTF_8.name());
            transportSender.send(session, msg);
            return AlertDeliveryResult.success("SMTP accepted (" + to.size() + " recipient(s))", null);
        } catch (Exception e) {
            log.debug("Email delivery failed: {}", e.toString());
            return AlertDeliveryResult.failure("email error: " + e.getMessage(), null);
        }
    }

    static String buildBody(Map<String, Object> alert, String event) {
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(event != null ? event : "alert.fired").append('\n');
        sb.append("source: db-kernel-gateway-console\n\n");
        append(sb, "name", alert.get("name"));
        append(sb, "severity", alert.get("severity"));
        append(sb, "metricKey", alert.get("metricKey"));
        append(sb, "comparator", alert.get("comparator"));
        append(sb, "thresholdValue", alert.get("thresholdValue"));
        append(sb, "value", alert.get("value"));
        append(sb, "valueMode", alert.get("valueMode"));
        append(sb, "instanceId", alert.get("instanceId"));
        append(sb, "scope", alert.get("scope"));
        append(sb, "thresholdId", alert.get("thresholdId"));
        append(sb, "firedAt", alert.get("firedAt"));
        append(sb, "message", alert.get("message"));
        return sb.toString();
    }

    private static void append(StringBuilder sb, String key, Object val) {
        if (val == null) {
            return;
        }
        sb.append(key).append(": ").append(val).append('\n');
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseRecipients(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof Collection<?> c) {
            for (Object o : c) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString().trim());
                }
            }
        } else if (raw instanceof String s) {
            for (String part : s.split("[,;\\s]+")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
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

    private static boolean asBool(Object o, boolean def) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o != null) {
            String s = o.toString().trim().toLowerCase();
            if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) {
                return true;
            }
            if ("false".equals(s) || "0".equals(s) || "no".equals(s)) {
                return false;
            }
        }
        return def;
    }
}
