package com.whosly.gateway.console;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyCapabilityTest {

    @Test
    void mysqlAndPostgresqlAreGatewayProxy() {
        for (String id : new String[]{"mysql", "MySQL", "mariadb", "postgresql", "postgres", "POSTGRESQL"}) {
            ProxyCapability mode = ProxyCapability.fromDbType(id);
            assertThat(mode).as(id).isEqualTo(ProxyCapability.GATEWAY);
            assertThat(mode.label()).isEqualTo("网关代理");
        }
    }

    @Test
    void sqlServerIsTransparentProxy() {
        for (String id : new String[]{"sqlserver", "mssql", "MSSQL", "SqlServer"}) {
            ProxyCapability mode = ProxyCapability.fromDbType(id);
            assertThat(mode).as(id).isEqualTo(ProxyCapability.TRANSPARENT);
            assertThat(mode.label()).isEqualTo("透明代理");
        }
    }

    @Test
    void oracleAndUnknownAreUnsupported() {
        assertThat(ProxyCapability.fromDbType("oracle")).isEqualTo(ProxyCapability.UNSUPPORTED);
        assertThat(ProxyCapability.fromDbType("Oracle").label()).isEqualTo("不支持");
        assertThat(ProxyCapability.fromDbType("unknown")).isEqualTo(ProxyCapability.UNSUPPORTED);
        assertThat(ProxyCapability.fromDbType(null)).isEqualTo(ProxyCapability.UNSUPPORTED);
        assertThat(ProxyCapability.fromDbType("")).isEqualTo(ProxyCapability.UNSUPPORTED);
        assertThat(ProxyCapability.fromDbType("  ")).isEqualTo(ProxyCapability.UNSUPPORTED);
    }

    @Test
    void shortHintsMatchConsoleCopy() {
        assertThat(ProxyCapability.GATEWAY.description()).isEqualTo("协议网关（观测/脱敏等）");
        assertThat(ProxyCapability.TRANSPARENT.description()).isEqualTo("字节透明转发");
        assertThat(ProxyCapability.UNSUPPORTED.description()).isNotBlank();
    }
}
