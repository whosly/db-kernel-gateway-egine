package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolErrorMapperTest {

    @Test
    void mapperContractProducesProtocolNativeMessage() {
        ProtocolErrorMapper mapper = error -> ProtocolMessage.typed('E', error.getMessage().getBytes());

        ProtocolMessage message = mapper.toErrorMessage(new ProtocolException("bad packet"));

        assertThat(message.type()).contains('E');
        assertThat(new String(message.payload())).isEqualTo("bad packet");
    }
}
