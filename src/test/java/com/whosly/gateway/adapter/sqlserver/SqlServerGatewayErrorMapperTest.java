package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.GatewayErrorMapping;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SqlServerGatewayErrorMapperTest {

    private final SqlServerGatewayErrorMapper mapper = new SqlServerGatewayErrorMapper();

    @Test
    void mapsIOExceptionToTabularErrorAndDone() {
        ProtocolMessage message = mapper.toErrorMessage(new IOException("connection refused"));
        GatewayErrorMapping mapping = GatewayErrorMapping.TARGET_UNAVAILABLE;

        assertThat(message.type()).contains((char) TdsPacketType.TABULAR_RESULT.getCode());
        assertThat(message.sequence()).hasValue(1);

        byte[] payload = message.payload();
        Optional<TdsErrorToken> error = TdsErrorToken.tryParse(payload, 0);
        assertThat(error).isPresent();
        assertThat(error.get().number()).isEqualTo(mapping.getMySqlErrno());
        assertThat(error.get().severityClass()).isEqualTo(20);
        assertThat(error.get().message()).isEqualTo(mapping.getDescription());
        assertThat(error.get().serverName()).isEqualTo(SqlServerGatewayErrorMapper.GATEWAY_SERVER_NAME);

        int doneAt = error.get().wireLength();
        assertThat(payload[doneAt] & 0xFF).isEqualTo(TdsDoneToken.TOKEN_TYPE);
        assertThat(payload[doneAt + 1] & 0xFF).isEqualTo(TdsDoneToken.STATUS_ERROR & 0xFF);
        assertThat(payload[doneAt + 2] & 0xFF).isEqualTo(0);
    }

    @Test
    void severityBandsMatchGatewayMappingKinds() {
        assertThat(SqlServerGatewayErrorMapper.severityClass(GatewayErrorMapping.RISK_DENIED)).isEqualTo(14);
        assertThat(SqlServerGatewayErrorMapper.severityClass(GatewayErrorMapping.RESOURCE_EXHAUSTED)).isEqualTo(17);
        assertThat(SqlServerGatewayErrorMapper.severityClass(GatewayErrorMapping.PROTOCOL_VIOLATION)).isEqualTo(16);
    }

    @Test
    void resolvesFailuresThroughSharedGatewayMapping() {
        assertThat(mapper.resolve(new IOException("io"))).isEqualTo(GatewayErrorMapping.TARGET_UNAVAILABLE);
    }
}
