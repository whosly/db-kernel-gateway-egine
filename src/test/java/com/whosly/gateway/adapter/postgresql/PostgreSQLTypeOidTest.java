package com.whosly.gateway.adapter.postgresql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostgreSQLTypeOidTest {

    @Test
    void resolvesCommonScalarAndArrayOids() {
        assertThat(PostgreSQLTypeOid.fromOid(16)).contains(PostgreSQLTypeOid.BOOL);
        assertThat(PostgreSQLTypeOid.fromOid(23)).contains(PostgreSQLTypeOid.INT4);
        assertThat(PostgreSQLTypeOid.fromOid(25)).contains(PostgreSQLTypeOid.TEXT);
        assertThat(PostgreSQLTypeOid.fromOid(2950)).contains(PostgreSQLTypeOid.UUID);
        assertThat(PostgreSQLTypeOid.fromOid(3802)).contains(PostgreSQLTypeOid.JSONB);
        assertThat(PostgreSQLTypeOid.fromOid(1007)).contains(PostgreSQLTypeOid.INT4_ARRAY);
    }

    @Test
    void exposesCategoryWithoutRewritingDatabaseMetadata() {
        assertThat(PostgreSQLTypeOid.JSONB.getCategory()).isEqualTo("json");
        assertThat(PostgreSQLTypeOid.INT8_ARRAY.getCategory()).isEqualTo("array");
        assertThat(PostgreSQLTypeOid.fromOid(999999)).isEmpty();
    }
}
