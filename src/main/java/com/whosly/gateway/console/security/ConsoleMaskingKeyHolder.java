package com.whosly.gateway.console.security;

import com.whosly.gateway.masking.MaskingCipher;
import com.whosly.gateway.masking.MaskingKeyProvider;

import java.util.Objects;
import java.util.Optional;

/**
 * Mutable holder for the active masking cipher used by instance rule compilation.
 * Source precedence: console-stored override → yaml config → none.
 */
public final class ConsoleMaskingKeyHolder {

    public enum Source {
        CONFIG, CONSOLE, NONE
    }

    private volatile State state;

    public ConsoleMaskingKeyHolder(MaskingCipher configCipher, String configKeyId) {
        if (configCipher != null) {
            this.state = new State(Optional.of(configCipher),
                    configKeyId != null && !configKeyId.isBlank() ? configKeyId : "default",
                    Source.CONFIG);
        } else {
            this.state = new State(Optional.empty(), "default", Source.NONE);
        }
    }

    public synchronized void applyConsoleKey(String keyId, byte[] keyBytes) {
        Objects.requireNonNull(keyBytes, "keyBytes");
        String id = keyId != null && !keyId.isBlank() ? keyId.trim() : "default";
        MaskingKeyProvider provider = MaskingKeyProvider.ofBase64(id,
                java.util.Base64.getEncoder().encodeToString(keyBytes));
        this.state = new State(Optional.of(new MaskingCipher(provider)), id, Source.CONSOLE);
    }

    public synchronized void clearConsoleKey(MaskingCipher configCipher, String configKeyId) {
        if (configCipher != null) {
            this.state = new State(Optional.of(configCipher),
                    configKeyId != null && !configKeyId.isBlank() ? configKeyId : "default",
                    Source.CONFIG);
        } else {
            this.state = new State(Optional.empty(), "default", Source.NONE);
        }
    }

    public Optional<MaskingCipher> cipher() {
        return state.cipher;
    }

    public String keyId() {
        return state.keyId;
    }

    public Source source() {
        return state.source;
    }

    public boolean configured() {
        return state.cipher.isPresent();
    }

    private record State(Optional<MaskingCipher> cipher, String keyId, Source source) {
    }
}
