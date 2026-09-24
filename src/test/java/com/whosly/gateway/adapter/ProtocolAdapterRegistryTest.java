package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.mysql.MySqlBackendSessionReset;
import com.whosly.gateway.adapter.postgresql.PostgreSQLBackendSessionReset;
import com.whosly.gateway.adapter.protocol.BackendSessionReset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolAdapterRegistryTest {

    @Test
    void builtInsCreateMysqlAndPostgresqlAdapters() {
        ProtocolAdapterRegistry registry = ProtocolAdapterRegistry.withBuiltIns();
        assertThat(registry.create("mysql")).isInstanceOf(MySqlProtocolAdapter.class);
        assertThat(registry.create("postgresql")).isInstanceOf(PostgreSQLProtocolAdapter.class);
        assertThat(registry.create("postgres")).isInstanceOf(PostgreSQLProtocolAdapter.class);
    }

    @Test
    void builtInsProvideProtocolSessionResets() {
        ProtocolAdapterRegistry registry = ProtocolAdapterRegistry.withBuiltIns();
        assertThat(registry.createSessionReset("mysql")).isInstanceOf(MySqlBackendSessionReset.class);
        assertThat(registry.createSessionReset("postgresql"))
                .isInstanceOf(PostgreSQLBackendSessionReset.class);
        assertThat(registry.createSessionReset("postgres"))
                .isInstanceOf(PostgreSQLBackendSessionReset.class);
    }

    @Test
    void reservedOracleAndSqlServerThrowClearUnsupportedMessage() {
        ProtocolAdapterRegistry registry = ProtocolAdapterRegistry.withBuiltIns();
        assertThatThrownBy(() -> registry.create("oracle"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("oracle")
                .hasMessageContaining("not implemented");
        assertThatThrownBy(() -> registry.create("sqlserver"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("sqlserver");
        assertThatThrownBy(() -> registry.create("mssql"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("sqlserver");
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
