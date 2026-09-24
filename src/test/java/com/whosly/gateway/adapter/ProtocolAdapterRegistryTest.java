package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.mysql.MySqlBackendSessionReset;
import com.whosly.gateway.adapter.postgresql.PostgreSQLBackendSessionReset;
import com.whosly.gateway.adapter.protocol.BackendSessionReset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolAdapterRegistryTest {

    @Test
    void builtInsCreateMysqlPostgresqlAndSqlServerAdapters() {
        ProtocolAdapterRegistry registry = ProtocolAdapterRegistry.withBuiltIns();
        assertThat(registry.create("mysql")).isInstanceOf(MySqlProtocolAdapter.class);
        assertThat(registry.create("postgresql")).isInstanceOf(PostgreSQLProtocolAdapter.class);
        assertThat(registry.create("postgres")).isInstanceOf(PostgreSQLProtocolAdapter.class);
        assertThat(registry.create("sqlserver")).isInstanceOf(SqlServerProtocolAdapter.class);
        assertThat(registry.create("mssql")).isInstanceOf(SqlServerProtocolAdapter.class);
    }

    @Test
    void builtInsProvideProtocolSessionResets() {
        ProtocolAdapterRegistry registry = ProtocolAdapterRegistry.withBuiltIns();
        assertThat(registry.createSessionReset("mysql")).isInstanceOf(MySqlBackendSessionReset.class);
        assertThat(registry.createSessionReset("postgresql"))
                .isInstanceOf(PostgreSQLBackendSessionReset.class);
        assertThat(registry.createSessionReset("postgres"))
                .isInstanceOf(PostgreSQLBackendSessionReset.class);
        assertThat(registry.createSessionReset("sqlserver")).isSameAs(BackendSessionReset.NONE);
        assertThat(registry.createSessionReset("mssql")).isSameAs(BackendSessionReset.NONE);
    }

    @Test
    void reservedOracleThrowsClearUnsupportedMessage() {
        ProtocolAdapterRegistry registry = ProtocolAdapterRegistry.withBuiltIns();
        assertThatThrownBy(() -> registry.create("oracle"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("oracle")
                .hasMessageContaining("not implemented");
    }

    @Test
    void unknownTypeIsRejected() {
        ProtocolAdapterRegistry registry = ProtocolAdapterRegistry.withBuiltIns();
        assertThatThrownBy(() -> registry.create("db2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported gateway.proxy-db-type");
    }

    @Test
    void customRegistrationCanAddNewDbType() {
        ProtocolAdapterRegistry registry = new ProtocolAdapterRegistry();
        registry.register("demo", MySqlProtocolAdapter::new, BackendSessionReset::none);
        assertThat(registry.create("demo")).isInstanceOf(MySqlProtocolAdapter.class);
        assertThat(registry.createSessionReset("DEMO")).isSameAs(BackendSessionReset.NONE);
    }
}
