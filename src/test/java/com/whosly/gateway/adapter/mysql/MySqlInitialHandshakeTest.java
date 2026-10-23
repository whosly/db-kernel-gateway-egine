package com.whosly.gateway.adapter.mysql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlInitialHandshakeTest {

    @Test
    void parsesProtocol10GreetingWithPluginAuth() {
        byte[] scramble = new byte[20];
        for (int i = 0; i < scramble.length; i++) {
            scramble[i] = (byte) (i + 1);
        }
        byte[] payload = MySqlWireFixtures.initialHandshake(
                "8.0.36", 42L, scramble, "caching_sha2_password");

        Optional<MySqlInitialHandshake> parsed = MySqlInitialHandshake.tryParse(payload);
        assertThat(parsed).isPresent();
        MySqlInitialHandshake hs = parsed.get();
        assertThat(hs.protocolVersion()).isEqualTo(10);
        assertThat(hs.serverVersion()).isEqualTo("8.0.36");
        assertThat(hs.connectionId()).isEqualTo(42L);
        assertThat(hs.authPluginName()).isEqualTo("caching_sha2_password");
        assertThat(hs.scramble()).isEqualTo(scramble);
    }

    @Test
    void emptyWhenNotProtocol10() {
        assertThat(MySqlInitialHandshake.tryParse(new byte[]{9, 0})).isEmpty();
        assertThat(MySqlInitialHandshake.tryParse(new byte[8])).isEmpty();
    }
}
