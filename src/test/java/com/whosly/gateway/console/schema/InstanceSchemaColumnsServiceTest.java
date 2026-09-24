package com.whosly.gateway.console.schema;

import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.console.persist.ConsoleInstanceRecord;
import com.whosly.gateway.console.persist.ConsoleInstanceStore;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import com.whosly.gateway.runtime.GatewayListenerRuntime.ManagedListener;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstanceSchemaColumnsServiceTest {

    @Test
    void buildJdbcUrlIsProtocolAgnostic() {
        assertThat(InstanceSchemaColumnsService.buildJdbcUrl("mysql", "127.0.0.1", 3306, "app"))
                .startsWith("jdbc:mysql://127.0.0.1:3306/app");
        assertThat(InstanceSchemaColumnsService.buildJdbcUrl("postgresql", "127.0.0.1", 5432, "app"))
                .startsWith("jdbc:postgresql://127.0.0.1:5432/app");
        assertThat(InstanceSchemaColumnsService.buildJdbcUrl("sqlserver", "127.0.0.1", 1433, "app"))
                .contains("jdbc:sqlserver://");
    }

    @Test
    void h2TargetReturnsColumns() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:schema_hint_target;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate target = new JdbcTemplate(ds);
        target.execute("CREATE TABLE demo_users (id INT, email VARCHAR(100), phone VARCHAR(32))");

        DriverManagerDataSource control = new DriverManagerDataSource();
        control.setDriverClassName("org.h2.Driver");
        control.setUrl("jdbc:h2:mem:schema_hint_control;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        control.setUsername("sa");
        control.setPassword("");
        ConsoleInstanceStore store = new ConsoleInstanceStore(new JdbcTemplate(control));
        Instant now = Instant.now();
        store.upsert(new ConsoleInstanceRecord(
                "h2-1", "hint", "h2", "0.0.0.0", 9, true,
                "127.0.0.1", 0, "jdbc:h2:mem:schema_hint_target;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "",
                now, now));

        ManagedListener listener = new ManagedListener(
                "h2-1", "hint", "h2", "0.0.0.0", 9, true, true,
                true, "127.0.0.1", 0,
                "jdbc:h2:mem:schema_hint_target;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", null, new GatewayRuntimeMetrics(), "console");

        GatewayListenerRuntime runtime = mock(GatewayListenerRuntime.class);
        when(runtime.find("h2-1")).thenReturn(Optional.of(listener));

        GatewayConfig cfg = new GatewayConfig();
        ReflectionTestUtils.setField(cfg, "targetPassword", "");
        ReflectionTestUtils.setField(cfg, "targetUsername", "sa");
        ReflectionTestUtils.setField(cfg, "targetHost", "127.0.0.1");
        ReflectionTestUtils.setField(cfg, "targetPort", 0);
        ReflectionTestUtils.setField(cfg, "targetDatabase", "");

        InstanceSchemaColumnsService service =
                new InstanceSchemaColumnsService(runtime, Optional.of(store), cfg);

        Map<String, Object> body = service.listColumns("h2-1", "DEMO_USERS");
        assertThat(body.get("count")).isNotNull();
        @SuppressWarnings("unchecked")
        var columns = (java.util.List<Map<String, Object>>) body.get("columns");
        assertThat(columns).isNotEmpty();
        assertThat(columns.stream().map(c -> String.valueOf(c.get("name")).toUpperCase()))
                .contains("EMAIL", "PHONE");
    }

    @Test
    void unknownTypeFailsClearly() {
        assertThatThrownBy(() -> InstanceSchemaColumnsService.buildJdbcUrl("oracle", "h", 1, "d"))
                .isInstanceOf(InstanceSchemaColumnsService.SchemaConnectException.class);
    }
}
