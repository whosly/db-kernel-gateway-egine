package com.whosly.gateway.adapter.mysql;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Builds MySQL AuthSwitchRequest payloads ({@code 0xFE} + plugin + scramble).
 *
 * <p>Used only on the deferred identity-routing <em>mismatch</em> path so the
 * client can re-authenticate against a real backend scramble without forging an
 * Initial Handshake.</p>
 */
public final class MySqlAuthSwitch {

    public static final int AUTH_SWITCH_HEADER = 0xFE;

    private MySqlAuthSwitch() {
    }

    /**
     * @param pluginName auth plugin (e.g. {@code caching_sha2_password})
     * @param scramble   plugin data / scramble bytes (NUL will be appended)
     */
    public static byte[] requestPayload(String pluginName, byte[] scramble) {
        Objects.requireNonNull(pluginName, "pluginName");
        Objects.requireNonNull(scramble, "scramble");
        byte[] name = pluginName.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[1 + name.length + 1 + scramble.length + 1];
        int cursor = 0;
        payload[cursor++] = (byte) AUTH_SWITCH_HEADER;
        System.arraycopy(name, 0, payload, cursor, name.length);
        cursor += name.length;
        payload[cursor++] = 0;
        System.arraycopy(scramble, 0, payload, cursor, scramble.length);
        cursor += scramble.length;
        payload[cursor] = 0;
        return payload;
    }
}
