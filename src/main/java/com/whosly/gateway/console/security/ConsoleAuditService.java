package com.whosly.gateway.console.security;

import com.whosly.gateway.console.persist.ConsoleAuditStore;
import com.whosly.gateway.console.persist.ConsoleAuditStore.ConsoleAuditRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes control-plane audit rows + SLF4J info. Never accepts password/key material in detail.
 */
@Service
public class ConsoleAuditService {

    private static final Logger log = LoggerFactory.getLogger(ConsoleAuditService.class);

    private final ConsoleAuditStore store;

    public ConsoleAuditService(ConsoleAuditStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public void record(String action, String instanceId, Map<String, ?> detail) {
        String detailJson = toSafeJson(detail);
        ConsoleAuditRecord row = store.insert(action, instanceId, detailJson, "console");
        log.info("console.audit action={} instanceId={} id={} detail={}",
                action, instanceId, row.id(), detailJson);
    }

    public List<ConsoleAuditRecord> list(int limit) {
        return list(limit, null);
    }

    public List<ConsoleAuditRecord> list(int limit, String action) {
        return store.listRecent(limit, action);
    }

    public int count() {
        return store.count();
    }

    private static String toSafeJson(Map<String, ?> detail) {
        if (detail == null || detail.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> e : detail.entrySet()) {
            String key = e.getKey();
            if (key == null || looksSecret(key)) {
                continue;
            }
            Object val = e.getValue();
            if (val == null) {
                continue;
            }
            String asString = String.valueOf(val);
            if (looksSecretValue(asString)) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(key)).append("\":");
            if (val instanceof Number || val instanceof Boolean) {
                sb.append(val);
            } else {
                sb.append('"').append(escape(asString)).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static boolean looksSecret(String key) {
        String k = key.toLowerCase();
        return k.contains("password") || k.contains("secret") || k.contains("key-base64")
                || k.contains("keybase64") || k.contains("token") || k.equals("key");
    }

    private static boolean looksSecretValue(String value) {
        return value.startsWith("enc:v1:") || value.length() > 200;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static Map<String, Object> detail(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
