package com.whosly.gateway.adapter.mysql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlAuthSwitchTest {

    @Test
    void buildsAuthSwitchRequestWithPluginAndScramble() {
        byte[] scramble = "SCRAMBLE1234567890AB".getBytes(StandardCharsets.US_ASCII);
        byte[] payload = MySqlAuthSwitch.requestPayload("mysql_native_password", scramble);
        assertThat(payload[0] & 0xFF).isEqualTo(MySqlAuthSwitch.AUTH_SWITCH_HEADER);
        String asString = new String(payload, 1, payload.length - 1, StandardCharsets.US_ASCII);
        assertThat(asString).startsWith("mysql_native_password\0");
        assertThat(asString).endsWith("\0");
        assertThat(payload).containsSequence(scramble);
    }
}
