package com.whosly.gateway.console;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.adapter.protocol.ProtocolSession;
import com.whosly.gateway.console.GatewayInstance.InstanceStatus;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import com.whosly.gateway.runtime.GatewayListenerRuntime.ManagedListener;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.concurrent.atomic.AtomicBoolean;

class GatewayInstanceRegistryTest {

    @Test
    void synthesizesDefaultInstanceWhenListEmpty() {
        ProtocolAdapter adapter = runningAdapter("MySQL", 33307);
        ManagedListener def = listener("default", "默认网关实例", "mysql", 33307, true, true, adapter);
        GatewayInstanceRegistry registry = registry(Map.of("default", def), "default");

        List<GatewayInstance> instances = registry.listInstances();
        assertThat(instances).hasSize(1);
        GatewayInstance only = instances.get(0);
        assertThat(only.id()).isEqualTo("default");
        assertThat(only.dbType()).isEqualTo("mysql");
        assertThat(only.bound()).isTrue();
        assertThat(only.status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(only.startable()).isTrue();
    }

    @Test
    void listsMixedTypesWithAllCreatableInstancesBound() {
        ProtocolAdapter mysqlAdapter = runningAdapter("MySQL", 33307);
        ProtocolAdapter pgAdapter = runningAdapter("PostgreSQL", 35433);
        ProtocolAdapter mssqlAdapter = runningAdapter("SQLServer", 31433);

        Map<String, ManagedListener> map = new LinkedHashMap<>();
        map.put("gw-mysql", listener("gw-mysql", "业务", "mysql", 33307, true, true, mysqlAdapter));
        map.put("gw-pg", listener("gw-pg", "分析", "postgresql", 35433, true, true, pgAdapter));
        map.put("gw-mssql", listener("gw-mssql", "遗留", "sqlserver", 31433, true, true, mssqlAdapter));

        GatewayInstanceRegistry registry = registry(map, "gw-mysql");
        List<GatewayInstance> instances = registry.listInstances();
        assertThat(instances).hasSize(3);

        GatewayInstance mysql = registry.findById("gw-mysql").orElseThrow();
        assertThat(mysql.bound()).isTrue();
        assertThat(mysql.status()).isEqualTo(InstanceStatus.RUNNING);

        GatewayInstance pg = registry.findById("gw-pg").orElseThrow();
        assertThat(pg.bound()).isTrue();
        assertThat(pg.status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(pg.startable()).isTrue();

        GatewayInstance mssql = registry.findById("gw-mssql").orElseThrow();
        assertThat(mssql.dbType()).isEqualTo("sqlserver");
        assertThat(mssql.bound()).isTrue();
        assertThat(mssql.status()).isEqualTo(InstanceStatus.RUNNING);
    }

    @Test
    void startStopIndependentlyPerBoundInstance() {
        ProtocolAdapter mysqlAdapter = startableAdapter("MySQL", 33307);
        ProtocolAdapter pgAdapter = startableAdapter("PostgreSQL", 35433);

        Map<String, ManagedListener> map = new LinkedHashMap<>();
        map.put("gw-mysql", listener("gw-mysql", "业务", "mysql", 33307, true, true, mysqlAdapter));
        map.put("gw-pg", listener("gw-pg", "分析", "postgresql", 35433, true, true, pgAdapter));

        GatewayInstanceRegistry registry = registry(map, "gw-mysql");

        Map<String, Object> started = registry.start("gw-mysql");
        verify(mysqlAdapter).start();
        assertThat(started.get("ok")).isEqualTo(true);

        Map<String, Object> startedPg = registry.start("gw-pg");
        verify(pgAdapter).start();
        assertThat(startedPg.get("ok")).isEqualTo(true);
    }

    private static ProtocolAdapter startableAdapter(String protocol, int port) {
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        AtomicBoolean running = new AtomicBoolean(false);
        when(adapter.isRunning()).thenAnswer(inv -> running.get());
        doAnswer(inv -> {
            running.set(true);
            return null;
        }).when(adapter).start();
        doAnswer(inv -> {
            running.set(false);
            return null;
        }).when(adapter).stop();
        when(adapter.getProtocolName()).thenReturn(protocol);
        when(adapter.getDefaultPort()).thenReturn(port);
        when(adapter.getActiveSessions()).thenReturn(List.<ProtocolSession>of());
        return adapter;
    }

    @Test
    void oracleInstanceIsUnsupported() {
        ManagedListener ora = listener("gw-ora", "Oracle", "oracle", 31521, true, false, null);
        GatewayInstanceRegistry registry = registry(Map.of("gw-ora", ora), "gw-ora");

        GatewayInstance instance = registry.findById("gw-ora").orElseThrow();
        assertThat(instance.status()).isEqualTo(InstanceStatus.UNSUPPORTED);
        assertThat(instance.startable()).isFalse();
        assertThat(instance.bound()).isFalse();
    }

    @Test
    void disabledInstanceIsDisabledNotUnbound() {
        ManagedListener disabled = listener("gw-off", "关闭", "mysql", 33307, false, true, null);
        GatewayInstanceRegistry registry = registry(Map.of("gw-off", disabled), "gw-off");
        GatewayInstance instance = registry.findById("gw-off").orElseThrow();
        assertThat(instance.status()).isEqualTo(InstanceStatus.DISABLED);
        assertThat(instance.bound()).isFalse();
    }

    private static GatewayInstanceRegistry registry(Map<String, ManagedListener> map, String legacyId) {
        return new GatewayInstanceRegistry(new GatewayListenerRuntime(map, legacyId));
    }

    private static ProtocolAdapter runningAdapter(String protocol, int port) {
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(adapter.isRunning()).thenReturn(true);
        when(adapter.getProtocolName()).thenReturn(protocol);
        when(adapter.getDefaultPort()).thenReturn(port);
        when(adapter.getActiveSessions()).thenReturn(List.<ProtocolSession>of());
        return adapter;
    }

    private static ManagedListener listener(String id, String name, String dbType, int port,
                                            boolean enabled, boolean creatable,
                                            ProtocolAdapter adapter) {
        return new ManagedListener(
                id, name, dbType, "0.0.0.0", port, enabled, creatable,
                true, "127.0.0.1", 3306, "db", "user",
                adapter, new GatewayRuntimeMetrics(), "config");
    }
}
