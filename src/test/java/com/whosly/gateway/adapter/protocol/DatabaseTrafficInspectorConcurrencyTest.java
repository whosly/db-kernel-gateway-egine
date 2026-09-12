package com.whosly.gateway.adapter.protocol;

import com.whosly.gateway.adapter.postgresql.PostgreSQLDatabaseEventExtractor;
import com.whosly.gateway.adapter.postgresql.PostgreSQLSession;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The relay runs one thread per direction and both share a single observer, so
 * these tests pin the two properties that make that sharing safe:
 *
 * <ul>
 *   <li>only protocol state is serialised — a slow audit sink in one direction
 *       must not stall the other direction;</li>
 *   <li>a session snapshot waits for the same monitor, so a reader never sees a
 *       half-applied protocol transition;</li>
 *   <li>no statement is lost, duplicated or reordered while both directions run
 *       against the same observer.</li>
 * </ul>
 */
class DatabaseTrafficInspectorConcurrencyTest {

    private static final int ROUNDS = 100;
    private static final int STATEMENTS_PER_ROUND = 50;

    @Test
    void doesNotHoldTheMonitorWhileTheAuditSinkRuns() throws Exception {
        PostgreSQLSession session = new PostgreSQLSession("pg-slow-sink");
        PostgreSQLDatabaseEventExtractor extractor =
                new PostgreSQLDatabaseEventExtractor("PostgreSQL", "pg-slow-sink", true, session);

        CountDownLatch sinkEntered = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        DatabaseTrafficObserver slowSink = event -> {
            sinkEntered.countDown();
            awaitQuietly(releaseSink, 5);
        };
        DatabaseTrafficInspector inspector = new DatabaseTrafficInspector(
                extractor::inspect, slowSink, DatabaseRiskPolicy.allowAll(), session, null);

        byte[] query = typedMessage('Q', "select 1");
        Thread clientDirection = new Thread(() -> inspector.intercept(
                RawBackedMessage.of(TrafficDirection.CLIENT_TO_TARGET, query, 0, query.length)));
        clientDirection.start();
        assertThat(sinkEntered.await(2, TimeUnit.SECONDS)).isTrue();

        try {
            /*
             * The sink is now blocked inside the client direction. The backend
             * direction must still be able to observe, which only holds while the
             * monitor is released before the sink runs.
             */
            byte[] readyForQuery = typedMessage('Z', new byte[]{'I'});
            CompletableFuture<Void> backendDirection = CompletableFuture.runAsync(() -> inspector.intercept(
                    RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, readyForQuery, 0, readyForQuery.length)));

            backendDirection.get(2, TimeUnit.SECONDS);
        } finally {
            releaseSink.countDown();
            clientDirection.join(2000);
        }
    }

    @Test
    void snapshotWaitsForTheMonitorTheDataPathHolds() throws Exception {
        PostgreSQLSession session = new PostgreSQLSession("pg-snapshot");
        CountDownLatch extractEntered = new CountDownLatch(1);
        CountDownLatch releaseExtract = new CountDownLatch(1);
        DatabaseEventExtractor blockingExtractor = (direction, bytes, offset, length) -> {
            extractEntered.countDown();
            awaitQuietly(releaseExtract, 5);
            return List.of();
        };
        DatabaseTrafficInspector inspector = new DatabaseTrafficInspector(
                blockingExtractor, DatabaseTrafficObserver.noop(), DatabaseRiskPolicy.allowAll(), session, null);

        byte[] query = typedMessage('Q', "select 1");
        Thread clientDirection = new Thread(() -> inspector.intercept(
                RawBackedMessage.of(TrafficDirection.CLIENT_TO_TARGET, query, 0, query.length)));
        clientDirection.start();
        assertThat(extractEntered.await(2, TimeUnit.SECONDS)).isTrue();

        try {
            // The extractor is blocked inside the monitor, so the reader must wait:
            // publishing a snapshot now would report a transition in progress.
            CompletableFuture<SessionSnapshot> pending = CompletableFuture.supplyAsync(session::snapshot);
            assertThatThrownBy(() -> pending.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
        } finally {
            releaseExtract.countDown();
        }

        clientDirection.join(2000);
        assertThat(session.snapshot()).isNotNull();
    }

    @Test
    void observesEveryStatementExactlyOnceAndInOrderUnderConcurrentDirections() throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                PostgreSQLSession session = new PostgreSQLSession("pg-concurrent");
                PostgreSQLDatabaseEventExtractor extractor =
                        new PostgreSQLDatabaseEventExtractor("PostgreSQL", "pg-concurrent", false, session);
                List<String> statements = new CopyOnWriteArrayList<>();
                DatabaseTrafficInspector inspector = new DatabaseTrafficInspector(
                        extractor::inspect, event -> statements.add(event.getStatement()),
                        DatabaseRiskPolicy.allowAll(), session, null);

                // Establish the session the way a real connection does.
                byte[] startup = startupMessage();
                inspector.intercept(
                        RawBackedMessage.of(TrafficDirection.CLIENT_TO_TARGET, startup, 0, startup.length));

                byte[] readyForQuery = typedMessage('Z', new byte[]{'I'});
                CompletableFuture<Void> clientDirection = runClientDirection(inspector, executorService);
                CompletableFuture<Void> backendDirection =
                        runBackendDirection(inspector, executorService, readyForQuery);
                clientDirection.get(10, TimeUnit.SECONDS);
                backendDirection.get(10, TimeUnit.SECONDS);

                assertThat(statements)
                        .as("round %d: every statement exactly once, in order, no duplication", round)
                        .hasSize(STATEMENTS_PER_ROUND);
                for (int index = 0; index < STATEMENTS_PER_ROUND; index++) {
                    assertThat(statements.get(index)).contains("select " + index);
                }

                /*
                 * Both directions left the session wherever they happened to finish
                 * (an unanswered query is legitimately EXECUTING). One more
                 * ReadyForQuery must still bring the shared state machine back to
                 * READY, which shows the concurrent hammering left no corrupt state.
                 */
                inspector.intercept(RawBackedMessage.of(
                        TrafficDirection.TARGET_TO_CLIENT, readyForQuery, 0, readyForQuery.length));
                assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);
            }
        } finally {
            executorService.shutdownNow();
        }
    }

    private static CompletableFuture<Void> runClientDirection(DatabaseTrafficInspector inspector,
                                                              ExecutorService executorService) {
        return CompletableFuture.runAsync(() -> {
            for (int index = 0; index < STATEMENTS_PER_ROUND; index++) {
                byte[] query = typedMessage('Q', "select " + index);
                inspector.intercept(
                        RawBackedMessage.of(TrafficDirection.CLIENT_TO_TARGET, query, 0, query.length));
            }
        }, executorService);
    }

    private static CompletableFuture<Void> runBackendDirection(DatabaseTrafficInspector inspector,
                                                               ExecutorService executorService,
                                                               byte[] readyForQuery) {
        return CompletableFuture.runAsync(() -> {
            for (int index = 0; index < STATEMENTS_PER_ROUND; index++) {
                inspector.intercept(
                        RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, readyForQuery, 0, readyForQuery.length));
            }
        }, executorService);
    }

    private static void awaitQuietly(CountDownLatch latch, long seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] typedMessage(char type, String payload) {
        return typedMessage(type, payload.getBytes(StandardCharsets.US_ASCII));
    }

    /** An untyped StartupMessage with no parameters (protocol 196608, length 8). */
    private static byte[] startupMessage() {
        return new byte[]{0x00, 0x00, 0x00, 0x08, 0x00, 0x03, 0x00, 0x00};
    }

    private static byte[] typedMessage(char type, byte[] payload) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(type);
        int length = payload.length + 4;
        outputStream.write((length >> 24) & 0xFF);
        outputStream.write((length >> 16) & 0xFF);
        outputStream.write((length >> 8) & 0xFF);
        outputStream.write(length & 0xFF);
        outputStream.writeBytes(payload);
        return outputStream.toByteArray();
    }
}
