package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayErrorMappingTest {

    @Test
    void mapsGatewayErrorsToMySqlAndPostgreSqlCodes() {
        GatewayErrorMapping targetUnavailable = GatewayErrorMapping.TARGET_UNAVAILABLE;

        assertThat(targetUnavailable.getMySqlErrno()).isEqualTo(1042);
        assertThat(targetUnavailable.getMySqlSqlState()).isEqualTo("08S01");
        assertThat(targetUnavailable.getPostgreSqlState()).isEqualTo("08006");
    }

    @Test
    void mapsUnsupportedGatewayFeatureAndResourceExhaustion() {
        assertThat(GatewayErrorMapping.UNSUPPORTED_GATEWAY_FEATURE.getMySqlErrno()).isEqualTo(1235);
        assertThat(GatewayErrorMapping.UNSUPPORTED_GATEWAY_FEATURE.getPostgreSqlState()).isEqualTo("0A000");
        assertThat(GatewayErrorMapping.RESOURCE_EXHAUSTED.getMySqlErrno()).isEqualTo(1041);
        assertThat(GatewayErrorMapping.RESOURCE_EXHAUSTED.getPostgreSqlState()).isEqualTo("53200");
    }
}
