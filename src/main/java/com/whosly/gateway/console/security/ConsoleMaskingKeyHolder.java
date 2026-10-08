package com.whosly.gateway.console.security;

import com.whosly.gateway.masking.MaskingCipher;
import com.whosly.gateway.masking.MaskingKeyProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Mutable holder for the active masking cipher used by instance rule compilation.
 *
 * <p>Source precedence: console-stored override → yaml config → none.</p>
 *
 * <p>During console rotation the holder keeps the active key plus up to
 * {@link #MAX_PREVIOUS_KEYS} previous keys so {@code enc:v1:<keyId>:…} payloads
 * remain decryptable. Encrypt always uses {@link #keyId()} (active).</p>
 */
public final class ConsoleMaskingKeyHolder {

    /** How many retired key ids to retain for decrypt after rotation. */
    public static final int MAX_PREVIOUS_KEYS = 5;

    public enum Source {
        CONFIG, CONSOLE, NONE
    }

    private volatile State state;

    public ConsoleMaskingKeyHolder(MaskingCipher configCipher, String configKeyId) {
        if (configCipher != null) {
            String id = configKeyId != null && !configKeyId.isBlank() ? configKeyId.trim() : "default";
            this.state = new State(Optional.of(configCipher), id, Source.CONFIG,
                    List.of(), Map.of());
        } else {
            this.state = new State(Optional.empty(), "default", Source.NONE, List.of(), Map.of());
        }
    }

    /**
     * Replace console override with a single active key (drops previous console keys).
     */
    public synchronized void applyConsoleKey(String keyId, byte[] keyBytes) {
        applyConsoleKey(keyId, keyBytes, false);
    }

    /**
     * Set a new active console key; optionally retain existing console keys for decrypt.
     *
     * @param keepPrevious when true, merge current console key ring (active+previous)
     *                     under the new active id (capped at {@link #MAX_PREVIOUS_KEYS} previous)
     */
    public synchronized void applyConsoleKey(String keyId, byte[] keyBytes, boolean keepPrevious) {
        Objects.requireNonNull(keyBytes, "keyBytes");
        String id = normalizeKeyId(keyId);
        Map<String, byte[]> keys = new LinkedHashMap<>();
        if (keepPrevious && state.source == Source.CONSOLE && !state.keysById.isEmpty()) {
            for (Map.Entry<String, byte[]> e : state.keysById.entrySet()) {
                keys.put(e.getKey(), e.getValue().clone());
            }
        }
        keys.put(id, keyBytes.clone());
        installConsoleKeys(id, keys);
    }

    /**
     * Install a full console key ring (active + previous). Used by bootstrap / rotation PUT.
     */
    public synchronized void applyConsoleKeys(String activeKeyId, Map<String, byte[]> keysById) {
        Objects.requireNonNull(keysById, "keysById");
        String id = normalizeKeyId(activeKeyId);
        if (!keysById.containsKey(id)) {
            throw new IllegalArgumentException("activeKeyId must be present in keysById: " + id);
        }
        Map<String, byte[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : keysById.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) {
                continue;
            }
            copy.put(e.getKey().trim(), e.getValue().clone());
        }
        if (!copy.containsKey(id)) {
            throw new IllegalArgumentException("activeKeyId must be present in keysById: " + id);
        }
        installConsoleKeys(id, copy);
    }

    private void installConsoleKeys(String activeKeyId, Map<String, byte[]> keys) {
        Map<String, byte[]> trimmed = trimKeys(activeKeyId, keys);
        MaskingKeyProvider provider = MaskingKeyProvider.ofKeys(trimmed);
        List<String> previous = new ArrayList<>();
        for (String kid : trimmed.keySet()) {
            if (!kid.equals(activeKeyId)) {
                previous.add(kid);
            }
        }
        Map<String, byte[]> stored = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : trimmed.entrySet()) {
            stored.put(e.getKey(), e.getValue().clone());
        }
        this.state = new State(Optional.of(new MaskingCipher(provider)), activeKeyId, Source.CONSOLE,
                List.copyOf(previous), Map.copyOf(stored));
    }

    /**
     * Keep at most {@link #MAX_PREVIOUS_KEYS} non-active keys (LRU by insertion: drop oldest).
     */
    private static Map<String, byte[]> trimKeys(String activeKeyId, Map<String, byte[]> keys) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put(activeKeyId, keys.get(activeKeyId));
        int prev = 0;
        // Prefer retaining keys in reverse insertion order (newest previous first).
        List<String> others = new ArrayList<>();
        for (String kid : keys.keySet()) {
            if (!kid.equals(activeKeyId)) {
                others.add(kid);
            }
        }
        Collections.reverse(others);
        for (String kid : others) {
            if (prev >= MAX_PREVIOUS_KEYS) {
                break;
            }
            out.put(kid, keys.get(kid));
            prev++;
        }
        return out;
    }

    public synchronized void clearConsoleKey(MaskingCipher configCipher, String configKeyId) {
        if (configCipher != null) {
            String id = configKeyId != null && !configKeyId.isBlank() ? configKeyId.trim() : "default";
            this.state = new State(Optional.of(configCipher), id, Source.CONFIG, List.of(), Map.of());
        } else {
            this.state = new State(Optional.empty(), "default", Source.NONE, List.of(), Map.of());
        }
    }

    public Optional<MaskingCipher> cipher() {
        return state.cipher;
    }

    /** Active key id used for encrypt. */
    public String keyId() {
        return state.keyId;
    }

    public Source source() {
        return state.source;
    }

    public boolean configured() {
        return state.cipher.isPresent();
    }

    /** Previous key ids retained for decrypt (never includes active). */
    public List<String> previousKeyIds() {
        return state.previousKeyIds;
    }

    /**
     * Snapshot of console key material (id → raw bytes clone). Empty when source is not CONSOLE.
     * Callers must wipe when done.
     */
    public synchronized Map<String, byte[]> consoleKeysSnapshot() {
        if (state.source != Source.CONSOLE) {
            return Map.of();
        }
        Map<String, byte[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : state.keysById.entrySet()) {
            copy.put(e.getKey(), e.getValue().clone());
        }
        return copy;
    }

    private static String normalizeKeyId(String keyId) {
        String id = keyId != null && !keyId.isBlank() ? keyId.trim() : "default";
        if (id.indexOf(':') >= 0) {
            throw new IllegalArgumentException("keyId must not contain ':'");
        }
        return id;
    }

    private record State(
            Optional<MaskingCipher> cipher,
            String keyId,
            Source source,
            List<String> previousKeyIds,
            Map<String, byte[]> keysById
    ) {
    }
}
