package com.whosly.gateway.adapter.mysql;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlHandshakeResponseCodecTest {

    @Test
    void parseAndRebuildRoundTripPreservesIdentity() {
        long caps = MySQLCapability.CLIENT_PROTOCOL_41.getFlag()
                | MySQLCapability.CLIENT_SECURE_CONNECTION.getFlag()
                | MySQLCapability.CLIENT_CONNECT_WITH_DB.getFlag()
                | MySQLCapability.CLIENT_PLUGIN_AUTH.getFlag();
        byte[] auth = new byte[20];
        for (int i = 0; i < auth.length; i++) {
            auth[i] = (byte) (0xA0 + i);
        }
        byte[] payload = MySqlWireFixtures.handshakeResponsePayload(
                caps, "appuser", auth, "demo", "mysql_native_password");

        Optional<MySqlHandshakeResponseCodec.Parsed> parsed = MySqlHandshakeResponseCodec.tryParse(payload);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().username()).isEqualTo("appuser");
        assertThat(parsed.get().database()).contains("demo");
        assertThat(parsed.get().authResponse()).isEqualTo(auth);
        assertThat(parsed.get().requestsSslOnly()).isFalse();

        byte[] newAuth = new byte[20];
        for (int i = 0; i < newAuth.length; i++) {
            newAuth[i] = (byte) (0x10 + i);
        }
        byte[] rebuilt = MySqlHandshakeResponseCodec.rebuild(parsed.get(), newAuth, "caching_sha2_password");
        Optional<MySqlHandshakeResponseCodec.Parsed> again = MySqlHandshakeResponseCodec.tryParse(rebuilt);
        assertThat(again).isPresent();
        assertThat(again.get().username()).isEqualTo("appuser");
        assertThat(again.get().database()).contains("demo");
        assertThat(again.get().authResponse()).isEqualTo(newAuth);
        assertThat(again.get().authPluginName()).contains("caching_sha2_password");
    }

    @Test
    void detectsSslRequestTruncatedPacket() {
        long caps = MySQLCapability.CLIENT_PROTOCOL_41.getFlag()
                | MySQLCapability.CLIENT_SSL.getFlag();
        byte[] sslRequest = new byte[32];
        sslRequest[0] = (byte) (caps & 0xFF);
        sslRequest[1] = (byte) ((caps >> 8) & 0xFF);
        sslRequest[2] = (byte) ((caps >> 16) & 0xFF);
        sslRequest[3] = (byte) ((caps >> 24) & 0xFF);
        Optional<MySqlHandshakeResponseCodec.Parsed> parsed = MySqlHandshakeResponseCodec.tryParse(sslRequest);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().requestsSslOnly()).isTrue();
    }
}
