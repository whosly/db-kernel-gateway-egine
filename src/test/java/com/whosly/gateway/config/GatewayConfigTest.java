package com.whosly.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayConfigTest {

    @Test
    void rejectsUnsupportedProtocolInsteadOfFallingBackToMysql() {
        GatewayConfig config = new GatewayConfig();
        ReflectionTestUtils.setField(config, "proxyDbType", "oracle");

        assertThatThrownBy(config::protocolAdapter)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported gateway proxy database protocol");
    }
}
