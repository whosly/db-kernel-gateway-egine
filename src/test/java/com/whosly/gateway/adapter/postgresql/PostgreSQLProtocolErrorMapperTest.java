package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.GatewayErrorMapping;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PostgreSQLProtocolErrorMapperTest {

    private final PostgreSQLProtocolErrorMapper mapper = new PostgreSQLProtocolErrorMapper();

    @Test
    void mapsIOExceptionToFatalErrorResponse() {
        ProtocolMessage message = mapper.toErrorMessage(new IOException("connection refused"));
        GatewayErrorMapping mapping = GatewayErrorMapping.TARGET_UNAVAILABLE;

        assertThat(message.type()).contains(PostgreSQLBackendMessageType.ERROR_RESPONSE.getCode());

        String payload = new String(message.payload(), StandardCharsets.UTF_8);
        assertThat(payload).contains(
                "SFATAL\u0000",
                "VFATAL\u0000",
                "C" + mapping.getPostgreSqlState() + "\u0000",
                "M" + mapping.getDescription() + "\u0000");
        assertThat(message.payload()[message.payload().length - 1]).isZero();
    }

    @Test
    void resolvesFailuresThroughSharedGatewayMapping() {
        assertThat(mapper.resolve(new IOException("io"))).isEqualTo(GatewayErrorMapping.TARGET_UNAVAILABLE);
    }
}
