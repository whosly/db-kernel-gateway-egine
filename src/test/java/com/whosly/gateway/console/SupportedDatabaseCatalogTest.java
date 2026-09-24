package com.whosly.gateway.console;

import com.whosly.gateway.adapter.ProtocolAdapterRegistry;
import com.whosly.gateway.config.GatewayCatalogProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SupportedDatabaseCatalogTest {

    private SupportedDatabaseCatalog catalog;

    @BeforeEach
    void setUp() {
        GatewayCatalogProperties props = new GatewayCatalogProperties();
        props.setDatabases(List.of(
                entry("mysql", "MySQL", true, "ga", 33307, 3306),
                entry("postgresql", "PostgreSQL", true, "ga", 35433, 5432),
                entry("sqlserver", "SQL Server", true, "partial", 31433, 1433),
                entry("oracle", "Oracle", false, "stub", 31521, 1521)
        ));
        catalog = new SupportedDatabaseCatalog(props, ProtocolAdapterRegistry.withBuiltIns());
    }

    @Test
    void listsConfiguredDatabasesWithRegistryCrossCheck() {
        List<SupportedDatabaseInfo> all = catalog.listAll();
        assertThat(all).hasSize(4);

        SupportedDatabaseInfo mysql = catalog.findById("mysql").orElseThrow();
        assertThat(mysql.maturity()).isEqualTo("ga");
        assertThat(mysql.registered()).isTrue();
        assertThat(mysql.creatable()).isTrue();
        assertThat(mysql.consoleCreateAllowed()).isTrue();

        SupportedDatabaseInfo pg = catalog.findById("postgresql").orElseThrow();
        assertThat(pg.consoleCreateAllowed()).isTrue();

        SupportedDatabaseInfo sqlserver = catalog.findById("sqlserver").orElseThrow();
        assertThat(sqlserver.maturity()).isEqualTo("partial");
        assertThat(sqlserver.creatable()).isTrue();
        assertThat(sqlserver.consoleCreateAllowed()).isTrue();

        SupportedDatabaseInfo oracle = catalog.findById("oracle").orElseThrow();
        assertThat(oracle.enabled()).isFalse();
        assertThat(oracle.registered()).isTrue();
        assertThat(oracle.creatable()).isFalse();
        assertThat(oracle.consoleCreateAllowed()).isFalse();
    }

    @Test
    void findByIdIsCaseInsensitive() {
        assertThat(catalog.findById("MySQL")).isPresent();
        assertThat(catalog.findById("POSTGRESQL")).isPresent();
        assertThat(catalog.findById("nope")).isEmpty();
    }

    private static GatewayCatalogProperties.DatabaseEntry entry(
            String id, String name, boolean enabled, String maturity,
            int proxyPort, int targetPort) {
        GatewayCatalogProperties.DatabaseEntry e = new GatewayCatalogProperties.DatabaseEntry();
        e.setId(id);
        e.setDisplayName(name);
        e.setEnabled(enabled);
        e.setMaturity(maturity);
        e.setDefaultProxyPort(proxyPort);
        e.setDefaultTargetPort(targetPort);
        e.setNotes("test");
        return e;
    }
}
