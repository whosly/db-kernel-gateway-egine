package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingBackendProviderTest {

    @Test
    void matchesByDatabaseName() throws Exception {
        try (ServerSocket appA = new ServerSocket(0);
             ServerSocket fallback = new ServerSocket(0)) {
            BackendProvider routeA = recording(appA.getLocalPort(), "A");
            BackendProvider routeFallback = recording(fallback.getLocalPort(), "F");
            RoutingRule rule = new RoutingRule("app_a", null,
                    List.of(new WeightedEndpoint(new BackendEndpoint("127.0.0.1", appA.getLocalPort()))));
            RoutingBackendProvider provider = new RoutingBackendProvider(
                    List.of(RoutingBackendProvider.bind(rule, routeA)), routeFallback);

            Socket socket = provider.acquire(RoutingContext.ofDatabase("APP_A"));
            assertThat(socket.getPort()).isEqualTo(appA.getLocalPort());
            provider.release(socket);
        }
    }

    @Test
    void matchesByUsername() throws Exception {
        try (ServerSocket readonly = new ServerSocket(0);
             ServerSocket fallback = new ServerSocket(0)) {
            RoutingRule rule = new RoutingRule(null, "readonly",
                    List.of(new WeightedEndpoint(new BackendEndpoint("127.0.0.1", readonly.getLocalPort()))));
            RoutingBackendProvider provider = new RoutingBackendProvider(
                    List.of(RoutingBackendProvider.bind(rule,
                            new DirectBackendProvider("127.0.0.1", readonly.getLocalPort(), 500))),
                    new DirectBackendProvider("127.0.0.1", fallback.getLocalPort(), 500));

            Socket socket = provider.acquire(RoutingContext.ofUsername("ReadOnly"));
            assertThat(socket.getPort()).isEqualTo(readonly.getLocalPort());
            provider.release(socket);
        }
    }

    @Test
    void fallsBackWhenNoRuleMatches() throws Exception {
        try (ServerSocket appA = new ServerSocket(0);
             ServerSocket fallback = new ServerSocket(0)) {
            RoutingRule rule = new RoutingRule("app_a", null,
                    List.of(new WeightedEndpoint(new BackendEndpoint("127.0.0.1", appA.getLocalPort()))));
            RoutingBackendProvider provider = new RoutingBackendProvider(
                    List.of(RoutingBackendProvider.bind(rule,
                            new DirectBackendProvider("127.0.0.1", appA.getLocalPort(), 500))),
                    new DirectBackendProvider("127.0.0.1", fallback.getLocalPort(), 500));

            Socket socket = provider.acquire(RoutingContext.ofDatabase("other"));
            assertThat(socket.getPort()).isEqualTo(fallback.getLocalPort());
            provider.release(socket);
        }
    }

    @Test
    void acquireWithoutContextUsesFallback() throws Exception {
        try (ServerSocket appA = new ServerSocket(0);
             ServerSocket fallback = new ServerSocket(0)) {
            RoutingRule rule = new RoutingRule("app_a", null,
                    List.of(new WeightedEndpoint(new BackendEndpoint("127.0.0.1", appA.getLocalPort()))));
            RoutingBackendProvider provider = new RoutingBackendProvider(
                    List.of(RoutingBackendProvider.bind(rule,
                            new DirectBackendProvider("127.0.0.1", appA.getLocalPort(), 500))),
                    new DirectBackendProvider("127.0.0.1", fallback.getLocalPort(), 500));

            Socket socket = provider.acquire();
            assertThat(socket.getPort()).isEqualTo(fallback.getLocalPort());
            provider.release(socket);
        }
    }

    @Test
    void disabledStyleEmptyRulesAlwaysFallback() throws Exception {
        try (ServerSocket fallback = new ServerSocket(0)) {
            RoutingBackendProvider provider = new RoutingBackendProvider(
                    List.of(),
                    new DirectBackendProvider("127.0.0.1", fallback.getLocalPort(), 500));
            Socket socket = provider.acquire(RoutingContext.of("u", "db"));
            assertThat(socket.getPort()).isEqualTo(fallback.getLocalPort());
            provider.release(socket);
        }
    }

    @Test
    void databaseAndUsernameMustBothMatchWhenBothConfigured() throws Exception {
        try (ServerSocket both = new ServerSocket(0);
             ServerSocket fallback = new ServerSocket(0)) {
            RoutingRule rule = new RoutingRule("app_a", "alice",
                    List.of(new WeightedEndpoint(new BackendEndpoint("127.0.0.1", both.getLocalPort()))));
            RoutingBackendProvider provider = new RoutingBackendProvider(
                    List.of(RoutingBackendProvider.bind(rule,
                            new DirectBackendProvider("127.0.0.1", both.getLocalPort(), 500))),
                    new DirectBackendProvider("127.0.0.1", fallback.getLocalPort(), 500));

            Socket hit = provider.acquire(RoutingContext.of("alice", "app_a"));
            assertThat(hit.getPort()).isEqualTo(both.getLocalPort());
            provider.release(hit);
            Socket missDb = provider.acquire(RoutingContext.of("alice", "other"));
            assertThat(missDb.getPort()).isEqualTo(fallback.getLocalPort());
            provider.release(missDb);
            Socket missUser = provider.acquire(RoutingContext.of("bob", "app_a"));
            assertThat(missUser.getPort()).isEqualTo(fallback.getLocalPort());
            provider.release(missUser);
        }
    }

    @Test
    void weightSelectionIsDeterministicWithSeededPicker() throws Exception {
        BackendEndpoint light = new BackendEndpoint("127.0.0.1", 1);
        BackendEndpoint heavy = new BackendEndpoint("127.0.0.1", 2);
        List<WeightedEndpoint> endpoints = List.of(
                new WeightedEndpoint(light, 1),
                new WeightedEndpoint(heavy, 3));

        // total weight 4; slot 0 → light; slots 1,2,3 → heavy
        assertThat(WeightedEndpointSelector.pickOne(endpoints, bound -> 0)).isEqualTo(light);
        assertThat(WeightedEndpointSelector.pickOne(endpoints, bound -> 1)).isEqualTo(heavy);
        assertThat(WeightedEndpointSelector.pickOne(endpoints, bound -> 3)).isEqualTo(heavy);

        List<BackendEndpoint> order = WeightedEndpointSelector.orderForAttempt(endpoints, bound -> 1);
        assertThat(order).containsExactly(heavy, light);
    }

    @Test
    void weightedFailoverConnectsToSeededPrimary() throws Exception {
        try (ServerSocket primary = new ServerSocket(0);
             ServerSocket secondary = new ServerSocket(0)) {
            BackendEndpoint a = new BackendEndpoint("127.0.0.1", primary.getLocalPort());
            BackendEndpoint b = new BackendEndpoint("127.0.0.1", secondary.getLocalPort());
            // Force pick of second endpoint (weight slot in [1,3) with weights 1+3).
            AtomicInteger slot = new AtomicInteger(1);
            WeightedFailoverBackendProvider provider = new WeightedFailoverBackendProvider(
                    List.of(new WeightedEndpoint(a, 1), new WeightedEndpoint(b, 3)),
                    500, GatewayRuntimeMetrics.noop(), 0L, System::currentTimeMillis, bound -> slot.get());

            Socket socket = provider.acquire();
            assertThat(socket.getPort()).isEqualTo(secondary.getLocalPort());
            provider.release(socket);
        }
    }

    @Test
    void fromSnapshotIgnoresUntrustedIdentity() {
        SessionSnapshot uncertain = new SessionSnapshot(
                "MySQL", "c1", ProtocolConnectionState.READY, ObservationConfidence.UNCERTAIN,
                false, java.util.Optional.of("alice"), java.util.Optional.of("app_a"),
                SessionDirtiness.clean(), java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        assertThat(RoutingContext.fromSnapshot(uncertain).isEmpty()).isTrue();

        SessionSnapshot confirmed = new SessionSnapshot(
                "MySQL", "c1", ProtocolConnectionState.READY, ObservationConfidence.CONFIRMED,
                false, java.util.Optional.of("alice"), java.util.Optional.of("app_a"),
                SessionDirtiness.clean(), java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        assertThat(RoutingContext.fromSnapshot(confirmed))
                .isEqualTo(RoutingContext.of("alice", "app_a"));
    }

    private static BackendProvider recording(int port, String ignoredName) {
        return new DirectBackendProvider("127.0.0.1", port, 500);
    }
}
