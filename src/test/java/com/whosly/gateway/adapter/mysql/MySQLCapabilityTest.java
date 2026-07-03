package com.whosly.gateway.adapter.mysql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MySQLCapabilityTest {

    @Test
    void whitelistContainsTlsCompressionPreparedStatementAndQueryAttributeCapabilities() {
        assertThat(MySQLCapability.isRecognized(MySQLCapability.CLIENT_SSL.getFlag())).isTrue();
        assertThat(MySQLCapability.isRecognized(MySQLCapability.CLIENT_COMPRESS.getFlag())).isTrue();
        assertThat(MySQLCapability.isRecognized(MySQLCapability.CLIENT_ZSTD_COMPRESSION_ALGORITHM.getFlag())).isTrue();
        assertThat(MySQLCapability.isRecognized(MySQLCapability.CLIENT_PS_MULTI_RESULTS.getFlag())).isTrue();
        assertThat(MySQLCapability.isRecognized(MySQLCapability.CLIENT_QUERY_ATTRIBUTES.getFlag())).isTrue();
    }

    @Test
    void unknownCapabilityIsNotRecognized() {
        assertThat(MySQLCapability.isRecognized(1L << 62)).isFalse();
    }
}
