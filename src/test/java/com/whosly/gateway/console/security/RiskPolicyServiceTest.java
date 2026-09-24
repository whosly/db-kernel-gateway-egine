package com.whosly.gateway.console.security;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.MutableDatabaseRiskPolicy;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.console.persist.ConsoleAuditStore;
import com.whosly.gateway.console.persist.RiskPolicyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskPolicyServiceTest {

    private RiskPolicyService service;
    private MutableDatabaseRiskPolicy mutable;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:risk-svc-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        RiskPolicyStore store = new RiskPolicyStore(jdbc);
        ConsoleAuditStore auditStore = new ConsoleAuditStore(jdbc);
        ConsoleAuditService audit = new ConsoleAuditService(auditStore);

        GatewayConfig config = new GatewayConfig();
        ReflectionTestUtils.setField(config, "riskDeniedOperations", "COM_PROCESS_KILL");
        ReflectionTestUtils.setField(config, "riskDeniedStatementKeywords", "");

        mutable = new MutableDatabaseRiskPolicy();
        service = new RiskPolicyService(store, mutable, config, audit);
        service.init();
    }

    @Test
    void yamlDefaultsUntilConsolePut() {
        Map<String, Object> view = service.getView();
        assertThat(view.get("source")).isEqualTo("yaml");
        assertThat(mutable.evaluate(event("COM_PROCESS_KILL", "x")).isAllowed()).isFalse();
        assertThat(mutable.evaluate(event("COM_QUERY", "drop table t")).isAllowed()).isTrue();
    }

    @Test
    void putOverridesAndHotApplies() {
        Map<String, Object> put = service.put(true, List.of(), List.of("drop table"));
        assertThat(put.get("ok")).isEqualTo(true);
        assertThat(put.get("source")).isEqualTo("console");
        assertThat(mutable.evaluate(event("COM_QUERY", "DROP TABLE accounts")).isAllowed()).isFalse();
        // YAML deny for COM_PROCESS_KILL no longer applies once console overrides
        assertThat(mutable.evaluate(event("COM_PROCESS_KILL", "x")).isAllowed()).isTrue();
    }

    @Test
    void disabledMeansAllowAll() {
        service.put(false, List.of("COM_QUIT"), List.of("drop"));
        assertThat(mutable.evaluate(event("COM_QUIT", "drop table t")).isAllowed()).isTrue();
    }

    private static DatabaseTrafficEvent event(String op, String stmt) {
        return DatabaseTrafficEvent.builder("MySQL", "s", op, stmt).build();
    }
}
