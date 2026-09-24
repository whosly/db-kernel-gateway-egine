package com.whosly.gateway.console.security;

import com.whosly.gateway.console.persist.ConsoleSecretsStore;
import com.whosly.gateway.console.persist.ConsoleSecretsStore.OpenedSecret;
import com.whosly.gateway.masking.MaskingCipher;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages console override of the masking AES key (encrypted in control-plane secrets).
 */
@Service
public class ConsoleMaskingKeyService {

    private static final Logger log = LoggerFactory.getLogger(ConsoleMaskingKeyService.class);

    private final ConsoleSecretsStore secretsStore;
    private final ConsoleSecretCipher secretCipher;
    private final ConsoleMaskingKeyHolder holder;
    private final MaskingCipher configCipher; // nullable
    private final String configKeyId;
    private final ObjectProvider<GatewayListenerRuntime> listenerRuntime;
    private final ConsoleAuditService audit;

    public ConsoleMaskingKeyService(ConsoleSecretsStore secretsStore,
                                    ConsoleSecretCipher secretCipher,
                                    ConsoleMaskingKeyHolder holder,
                                    ObjectProvider<GatewayListenerRuntime> listenerRuntime,
                                    ConsoleAuditService audit,
                                    @Value("${gateway.masking.key-base64:}") String configKeyBase64,
                                    @Value("${gateway.masking.key-id:default}") String configKeyId) {
        this.secretsStore = Objects.requireNonNull(secretsStore, "secretsStore");
        this.secretCipher = Objects.requireNonNull(secretCipher, "secretCipher");
        this.holder = Objects.requireNonNull(holder, "holder");
        this.listenerRuntime = Objects.requireNonNull(listenerRuntime, "listenerRuntime");
        this.audit = Objects.requireNonNull(audit, "audit");
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

    private void bootstrapFromStore() {
        Optional<OpenedSecret> existing = secretsStore.find(ConsoleSecretsStore.KEY_MASKING);
        if (existing.isEmpty()) {
            return;
        }
        OpenedSecret secret = existing.get();
        String keyId = parseKeyId(secret.metaJson()).orElse(configKeyId);
        try {
            byte[] keyBytes = ConsoleSecretCipher.decodeAesKey(secret.plaintext());
            holder.applyConsoleKey(keyId, keyBytes);
            ConsoleSecretCipher.wipe(keyBytes);
            log.info("Loaded console masking key override (keyId={}, source=console)", keyId);
        } catch (RuntimeException e) {
            log.warn("Failed to load console masking key override: {}", e.getMessage());
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured", holder.configured());
        body.put("keyId", holder.configured() ? holder.keyId() : null);
        body.put("source", holder.source().name().toLowerCase());
        body.put("consoleMasterKeyConfigured", secretCipher.isMasterKeyConfigured());
        return body;
    }

    public Map<String, Object> putKey(String keyId, String keyBase64) {
        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new IllegalArgumentException("keyBase64 is required");
        }
        if (!secretCipher.isMasterKeyConfigured()) {
            throw new IllegalStateException(
                    "需要配置 gateway.console.secret-key-base64 才能在管控台加密保存脱敏密钥");
        }
        byte[] keyBytes = ConsoleSecretCipher.decodeAesKey(keyBase64);
        String id = keyId != null && !keyId.isBlank() ? keyId.trim() : "default";
        String meta = "{\"keyId\":\"" + id.replace("\"", "") + "\"}";
        secretsStore.put(ConsoleSecretsStore.KEY_MASKING, keyBase64.trim(), meta);
        holder.applyConsoleKey(id, keyBytes);
        ConsoleSecretCipher.wipe(keyBytes);
        reloadAllEngines();
        audit.record("masking-key.put", null, ConsoleAuditService.detail("keyId", id, "source", "console"));
        Map<String, Object> body = status();
        body.put("ok", true);
        body.put("message", "脱敏密钥已保存（加密存储）并热重载");
        return body;
    }

    public Map<String, Object> clearKey() {
        boolean deleted = secretsStore.delete(ConsoleSecretsStore.KEY_MASKING);
        holder.clearConsoleKey(configCipher, configKeyId);
        reloadAllEngines();
        audit.record("masking-key.delete", null,
                ConsoleAuditService.detail("deleted", deleted, "fallback", holder.source().name().toLowerCase()));
        Map<String, Object> body = status();
        body.put("ok", true);
        body.put("cleared", deleted);
        body.put("message", "已清除管控台脱敏密钥；回退配置源=" + holder.source().name().toLowerCase());
        return body;
    }

    private void reloadAllEngines() {
        GatewayListenerRuntime runtime = listenerRuntime.getIfAvailable();
        if (runtime != null) {
            runtime.reloadAllMasking();
        }
    }

    private static Optional<String> parseKeyId(String metaJson) {
        if (metaJson == null || metaJson.isBlank()) {
            return Optional.empty();
        }
        int idx = metaJson.indexOf("\"keyId\"");
        if (idx < 0) {
            return Optional.empty();
        }
        int colon = metaJson.indexOf(':', idx);
        int q1 = metaJson.indexOf('"', colon + 1);
        int q2 = metaJson.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) {
            return Optional.empty();
        }
        return Optional.of(metaJson.substring(q1 + 1, q2));
    }
}
