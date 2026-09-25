package com.whosly.gateway.console.security;

import com.whosly.gateway.console.persist.ConsoleSecretsStore;
import com.whosly.gateway.console.persist.ConsoleSecretsStore.OpenedSecret;
import com.whosly.gateway.console.persist.MaskingRuleStore;
import com.whosly.gateway.masking.MaskingCipher;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages console override of the masking AES key (encrypted in control-plane secrets).
 *
 * <p>Supports key rotation: active key for encrypt; previous keys retained for decrypt of
 * {@code enc:v1:<keyId>:…} payloads. Status never returns key material.</p>
 */
@Service
public class ConsoleMaskingKeyService {

    private static final Logger log = LoggerFactory.getLogger(ConsoleMaskingKeyService.class);

    /** Probe plaintext for {@link #verify()}; never returned to clients. */
    private static final String VERIFY_SAMPLE = "gateway-masking-key-verify";

    private final ConsoleSecretsStore secretsStore;
    private final ConsoleSecretCipher secretCipher;
    private final ConsoleMaskingKeyHolder holder;
    private final MaskingCipher configCipher; // nullable
    private final String configKeyId;
    private final ObjectProvider<GatewayListenerRuntime> listenerRuntime;
    private final ConsoleAuditService audit;
    private final MaskingRuleStore maskingRuleStore; // nullable

    @Autowired
    public ConsoleMaskingKeyService(ConsoleSecretsStore secretsStore,
                                    ConsoleSecretCipher secretCipher,
                                    ConsoleMaskingKeyHolder holder,
                                    ObjectProvider<GatewayListenerRuntime> listenerRuntime,
                                    ConsoleAuditService audit,
                                    ObjectProvider<MaskingRuleStore> maskingRuleStore,
                                    @Value("${gateway.masking.key-base64:}") String configKeyBase64,
                                    @Value("${gateway.masking.key-id:default}") String configKeyId) {
        this.secretsStore = Objects.requireNonNull(secretsStore, "secretsStore");
        this.secretCipher = Objects.requireNonNull(secretCipher, "secretCipher");
        this.holder = Objects.requireNonNull(holder, "holder");
        this.listenerRuntime = Objects.requireNonNull(listenerRuntime, "listenerRuntime");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.maskingRuleStore = maskingRuleStore != null ? maskingRuleStore.getIfAvailable() : null;
        this.configKeyId = configKeyId != null && !configKeyId.isBlank() ? configKeyId : "default";
        if (configKeyBase64 != null && !configKeyBase64.isBlank()) {
            this.configCipher = new MaskingCipher(
                    com.whosly.gateway.masking.MaskingKeyProvider.ofBase64(
                            this.configKeyId, configKeyBase64.trim()));
        } else {
            this.configCipher = null;
        }
        bootstrapFromStore();
    }

    /** Test constructor without MaskingRuleStore. */
    public ConsoleMaskingKeyService(ConsoleSecretsStore secretsStore,
                                    ConsoleSecretCipher secretCipher,
                                    ConsoleMaskingKeyHolder holder,
                                    ObjectProvider<GatewayListenerRuntime> listenerRuntime,
                                    ConsoleAuditService audit,
                                    String configKeyBase64,
                                    String configKeyId) {
        this(secretsStore, secretCipher, holder, listenerRuntime, audit,
                emptyProvider(), configKeyBase64, configKeyId);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MaskingRuleStore> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public MaskingRuleStore getObject() {
                return null;
            }

            @Override
            public MaskingRuleStore getObject(Object... args) {
                return null;
            }

            @Override
            public MaskingRuleStore getIfAvailable() {
                return null;
            }

            @Override
            public MaskingRuleStore getIfUnique() {
                return null;
            }
        };
    }

    private void bootstrapFromStore() {
        Optional<OpenedSecret> existing = secretsStore.find(ConsoleSecretsStore.KEY_MASKING);
        if (existing.isEmpty()) {
            return;
        }
        OpenedSecret secret = existing.get();
        try {
            KeyBundle bundle = KeyBundle.parse(secret.plaintext(), secret.metaJson(), configKeyId);
            holder.applyConsoleKeys(bundle.activeKeyId(), bundle.keysById());
            wipe(bundle.keysById());
            log.info("Loaded console masking key override (keyId={}, previous={}, source=console)",
                    holder.keyId(), holder.previousKeyIds().size());
        } catch (RuntimeException e) {
            log.warn("Failed to load console masking key override: {}", e.getMessage());
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean configured = holder.configured();
        String activeKeyId = configured ? holder.keyId() : null;
        int encryptEnabled = countEnabledEncryptRules();
        body.put("configured", configured);
        body.put("keyId", activeKeyId);
        body.put("activeKeyId", activeKeyId);
        body.put("source", holder.source().name().toLowerCase());
        body.put("previousKeyIds", List.copyOf(holder.previousKeyIds()));
        body.put("encryptRulesCanBind", configured);
        body.put("encryptRuleCount", encryptEnabled);
        body.put("encryptRulesWithoutKey", configured ? 0 : encryptEnabled);
        body.put("consoleMasterKeyConfigured", secretCipher.isMasterKeyConfigured());
        body.put("requireSecretEncryption", secretCipher.isRequireSecretEncryption());
        body.put("allowsPlaintextWrites", secretCipher.allowsPlaintextWrites());
        body.put("secretEncryption", secretCipher.status());
        if (!configured && encryptEnabled > 0) {
            body.put("warning", "存在 " + encryptEnabled
                    + " 条启用的 encrypt 规则，但当前无脱敏密钥；请配置 yaml 或在管控台设置密钥，否则重载将失败。");
        }
        return body;
    }

    /**
     * Persist and activate a new masking key.
     *
     * @param keyId          active key id (default {@code default})
     * @param keyBase64      AES key material
     * @param previousKeyId  optional: ensure this id remains in the decrypt ring (must already exist
     *                       when keepPrevious and ring is non-empty, or equals outgoing active)
     * @param keepPrevious   when null/true, retain existing console keys for decrypt (capped)
     */
    public Map<String, Object> putKey(String keyId, String keyBase64,
                                      String previousKeyId, Boolean keepPrevious) {
        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new IllegalArgumentException("keyBase64 is required");
        }
        if (!secretCipher.isMasterKeyConfigured()) {
            throw new IllegalStateException(
                    "需要配置 gateway.console.secret-key-base64 才能在管控台加密保存脱敏密钥");
        }
        byte[] keyBytes = ConsoleSecretCipher.decodeAesKey(keyBase64);
        String id = keyId != null && !keyId.isBlank() ? keyId.trim() : "default";
        if (id.indexOf(':') >= 0) {
            ConsoleSecretCipher.wipe(keyBytes);
            throw new IllegalArgumentException("keyId must not contain ':'");
        }
        boolean retain = keepPrevious == null || keepPrevious;

        Map<String, byte[]> ring = new LinkedHashMap<>();
        if (retain && holder.source() == ConsoleMaskingKeyHolder.Source.CONSOLE) {
            ring.putAll(holder.consoleKeysSnapshot());
        }
        if (previousKeyId != null && !previousKeyId.isBlank()) {
            String prev = previousKeyId.trim();
            if (prev.indexOf(':') >= 0) {
                wipe(ring);
                ConsoleSecretCipher.wipe(keyBytes);
                throw new IllegalArgumentException("previousKeyId must not contain ':'");
            }
            if (!prev.equals(id) && !ring.containsKey(prev)
                    && holder.configured() && prev.equals(holder.keyId())) {
                // Outgoing active may only live in holder cipher (config source) — cannot copy bytes.
                // For console source, snapshot already has it.
            }
            if (!prev.equals(id) && retain && holder.source() == ConsoleMaskingKeyHolder.Source.CONSOLE
                    && !ring.containsKey(prev)) {
                wipe(ring);
                ConsoleSecretCipher.wipe(keyBytes);
                throw new IllegalArgumentException(
                        "previousKeyId 不在当前密钥环中: " + prev + "（已知: " + ring.keySet() + "）");
            }
        }
        ring.put(id, keyBytes.clone());
        holder.applyConsoleKeys(id, ring);

        KeyBundle bundle = KeyBundle.fromHolder(holder);
        String sealedPlain = bundle.toStoragePlaintext();
        String meta = bundle.toMetaJson();
        secretsStore.put(ConsoleSecretsStore.KEY_MASKING, sealedPlain, meta);
        wipe(ring);
        ConsoleSecretCipher.wipe(keyBytes);
        wipe(bundle.keysById());

        Map<String, Object> reload = reloadAllEngines();
        audit.record("masking-key.put", null, ConsoleAuditService.detail(
                "keyId", id,
                "source", "console",
                "previousCount", holder.previousKeyIds().size(),
                "keepPrevious", retain));
        Map<String, Object> body = status();
        body.put("ok", true);
        body.put("message", "脱敏密钥已保存（加密存储）并热重载");
        body.put("reload", reload);
        return body;
    }

    public Map<String, Object> putKey(String keyId, String keyBase64) {
        return putKey(keyId, keyBase64, null, true);
    }

    public Map<String, Object> clearKey() {
        boolean deleted = secretsStore.delete(ConsoleSecretsStore.KEY_MASKING);
        holder.clearConsoleKey(configCipher, configKeyId);
        Map<String, Object> reload = reloadAllEngines();
        audit.record("masking-key.delete", null,
                ConsoleAuditService.detail("deleted", deleted, "fallback", holder.source().name().toLowerCase()));
        Map<String, Object> body = status();
        body.put("ok", true);
        body.put("cleared", deleted);
        body.put("message", "已清除管控台脱敏密钥；回退配置源=" + holder.source().name().toLowerCase());
        body.put("reload", reload);
        if (!holder.configured() && countEnabledEncryptRules() > 0) {
            body.put("ok", false);
            body.put("warning", body.get("warning"));
            body.put("message", "已清除管控台密钥，但仍有 encrypt 规则且无可用密钥；相关实例重载已失败闭合，请尽快配置密钥。");
        }
        return body;
    }

    /**
     * Encrypt→decrypt round-trip with the active key. Never returns key or ciphertext.
     */
    public Map<String, Object> verify() {
        if (!holder.configured()) {
            throw new IllegalArgumentException(
                    "无可用脱敏密钥：设置 gateway.masking.key-base64 或在管控台配置脱敏密钥后再自检");
        }
        MaskingCipher cipher = holder.cipher().orElseThrow();
        String keyId = holder.keyId();
        try {
            String encrypted = cipher.encrypt(keyId, VERIFY_SAMPLE);
            String roundTrip = MaskingCipher.isPrefixed(encrypted)
                    ? cipher.decrypt(encrypted)
                    : cipher.decrypt(keyId, encrypted);
            if (!VERIFY_SAMPLE.equals(roundTrip)) {
                throw new IllegalStateException("加密自检失败：往返明文不一致");
            }
            // Ensure embedded key id is present for new format
            if (!MaskingCipher.isPrefixed(encrypted)) {
                throw new IllegalStateException("加密自检失败：密文未嵌入 keyId");
            }
            Optional<String> embedded = MaskingCipher.extractKeyId(encrypted);
            if (embedded.isEmpty() || !keyId.equals(embedded.get())) {
                throw new IllegalStateException("加密自检失败：嵌入 keyId 不匹配");
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("加密自检失败：" + e.getMessage(), e);
        }
        audit.record("masking-key.verify", null, ConsoleAuditService.detail("keyId", keyId, "ok", true));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("keyId", keyId);
        body.put("message", "加密自检通过（encrypt→decrypt 往返）");
        return body;
    }

    private Map<String, Object> reloadAllEngines() {
        GatewayListenerRuntime runtime = listenerRuntime.getIfAvailable();
        if (runtime != null) {
            return runtime.reloadAllMasking();
        }
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("ok", true);
        empty.put("reloaded", 0);
        empty.put("skipped", 0);
        empty.put("message", "无运行时，跳过热重载");
        return empty;
    }

    private int countEnabledEncryptRules() {
        if (maskingRuleStore == null) {
            return 0;
        }
        return maskingRuleStore.countEnabledByStrategy("encrypt");
    }

    private static void wipe(Map<String, byte[]> keys) {
        if (keys == null) {
            return;
        }
        for (byte[] b : keys.values()) {
            ConsoleSecretCipher.wipe(b);
        }
    }

    /**
     * Storage bundle: JSON envelope for multi-key, or legacy single Base64.
     */
    static final class KeyBundle {
        private final String activeKeyId;
        private final Map<String, byte[]> keysById;

        KeyBundle(String activeKeyId, Map<String, byte[]> keysById) {
            this.activeKeyId = activeKeyId;
            this.keysById = keysById;
        }

        String activeKeyId() {
            return activeKeyId;
        }

        Map<String, byte[]> keysById() {
            return keysById;
        }

        static KeyBundle fromHolder(ConsoleMaskingKeyHolder holder) {
            Map<String, byte[]> snap = holder.consoleKeysSnapshot();
            return new KeyBundle(holder.keyId(), snap);
        }

        String toStoragePlaintext() {
            // Compact JSON without external deps
            StringBuilder sb = new StringBuilder(128);
            sb.append("{\"v\":1,\"activeKeyId\":\"").append(escape(activeKeyId)).append("\",\"keys\":{");
            boolean first = true;
            for (Map.Entry<String, byte[]> e : keysById.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(e.getKey())).append("\":\"")
                        .append(Base64.getEncoder().encodeToString(e.getValue())).append('"');
            }
            sb.append("}}");
            return sb.toString();
        }

        String toMetaJson() {
            StringBuilder prev = new StringBuilder("[");
            boolean first = true;
            for (String kid : keysById.keySet()) {
                if (kid.equals(activeKeyId)) {
                    continue;
                }
                if (!first) {
                    prev.append(',');
                }
                first = false;
                prev.append('"').append(escape(kid)).append('"');
            }
            prev.append(']');
            return "{\"keyId\":\"" + escape(activeKeyId) + "\",\"previousKeyIds\":" + prev + "}";
        }

        static KeyBundle parse(String plaintext, String metaJson, String fallbackKeyId) {
            if (plaintext == null || plaintext.isBlank()) {
                throw new IllegalArgumentException("empty masking secret");
            }
            String trimmed = plaintext.trim();
            if (trimmed.startsWith("{")) {
                return parseJsonEnvelope(trimmed);
            }
            // Legacy: raw Base64 key bytes
            String keyId = parseKeyIdFromMeta(metaJson).orElse(fallbackKeyId);
            byte[] keyBytes = ConsoleSecretCipher.decodeAesKey(trimmed);
            Map<String, byte[]> keys = new LinkedHashMap<>();
            keys.put(keyId, keyBytes);
            return new KeyBundle(keyId, keys);
        }

        private static KeyBundle parseJsonEnvelope(String json) {
            String active = extractJsonString(json, "activeKeyId")
                    .orElseThrow(() -> new IllegalArgumentException("activeKeyId missing in masking secret"));
            Map<String, byte[]> keys = new LinkedHashMap<>();
            int keysIdx = json.indexOf("\"keys\"");
            if (keysIdx < 0) {
                throw new IllegalArgumentException("keys missing in masking secret");
            }
            int brace = json.indexOf('{', keysIdx);
            int end = json.lastIndexOf('}');
            if (brace < 0 || end <= brace) {
                throw new IllegalArgumentException("keys object malformed");
            }
            // keys object may be nested; find matching close for keys {
            int depth = 0;
            int keysEnd = -1;
            for (int i = brace; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        keysEnd = i;
                        break;
                    }
                }
            }
            if (keysEnd < 0) {
                throw new IllegalArgumentException("keys object malformed");
            }
            String keysObj = json.substring(brace + 1, keysEnd);
            // Parse "id":"base64" pairs
            int pos = 0;
            while (pos < keysObj.length()) {
                int q1 = keysObj.indexOf('"', pos);
                if (q1 < 0) {
                    break;
                }
                int q2 = keysObj.indexOf('"', q1 + 1);
                if (q2 < 0) {
                    break;
                }
                String kid = keysObj.substring(q1 + 1, q2);
                int colon = keysObj.indexOf(':', q2 + 1);
                int v1 = keysObj.indexOf('"', colon + 1);
                int v2 = keysObj.indexOf('"', v1 + 1);
                if (colon < 0 || v1 < 0 || v2 < 0) {
                    break;
                }
                String b64 = keysObj.substring(v1 + 1, v2);
                keys.put(kid, Base64.getDecoder().decode(b64.getBytes(StandardCharsets.US_ASCII)));
                pos = v2 + 1;
            }
            if (!keys.containsKey(active)) {
                throw new IllegalArgumentException("activeKeyId not in keys map");
            }
            return new KeyBundle(active, keys);
        }

        private static Optional<String> extractJsonString(String json, String field) {
            String needle = "\"" + field + "\"";
            int idx = json.indexOf(needle);
            if (idx < 0) {
                return Optional.empty();
            }
            int colon = json.indexOf(':', idx + needle.length());
            int q1 = json.indexOf('"', colon + 1);
            int q2 = json.indexOf('"', q1 + 1);
            if (q1 < 0 || q2 < 0) {
                return Optional.empty();
            }
            return Optional.of(json.substring(q1 + 1, q2));
        }

        private static Optional<String> parseKeyIdFromMeta(String metaJson) {
            if (metaJson == null || metaJson.isBlank()) {
                return Optional.empty();
            }
            return extractJsonString(metaJson, "keyId");
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
