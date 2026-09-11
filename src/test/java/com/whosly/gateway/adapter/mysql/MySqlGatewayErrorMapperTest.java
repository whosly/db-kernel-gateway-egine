package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.GatewayErrorMapping;
import com.whosly.gateway.adapter.protocol.ProtocolException;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlGatewayErrorMapperTest {

    private final MySqlGatewayErrorMapper mapper = new MySqlGatewayErrorMapper();

    @Test
    void mapsIOExceptionToTargetUnavailableErrorPacket() {
        ProtocolMessage message = mapper.toErrorMessage(new IOException("connection refused"));

        assertThat(message.sequence()).hasValue(0);
        byte[] payload = message.payload();
        assertThat(payload[0] & 0xFF).isEqualTo(0xFF);
        assertThat(payload[1] & 0xFF).isEqualTo(GatewayErrorMapping.TARGET_UNAVAILABLE.getMySqlErrno() & 0xFF);
        assertThat(payload[2] & 0xFF).isEqualTo((GatewayErrorMapping.TARGET_UNAVAILABLE.getMySqlErrno() >> 8) & 0xFF);
        assertThat(new String(payload, 3, 6, StandardCharsets.US_ASCII)).isEqualTo("#08S01");
        assertThat(new String(payload, 9, payload.length - 9, StandardCharsets.UTF_8))
                .isEqualTo(GatewayErrorMapping.TARGET_UNAVAILABLE.getDescription());
    }

    @Test
    void mapsTimeoutProtocolAndUnexpectedFailuresToDedicatedMappings() {
        assertThat(mapper.resolve(new SocketTimeoutException("timeout")))
                .isEqualTo(GatewayErrorMapping.CONNECTION_TIMEOUT);
        assertThat(mapper.resolve(new ProtocolException("bad frame")))
                .isEqualTo(GatewayErrorMapping.PROTOCOL_VIOLATION);
        assertThat(mapper.resolve(new IllegalStateException("boom")))
                .isEqualTo(GatewayErrorMapping.INTERNAL_GATEWAY_ERROR);
    }
}
