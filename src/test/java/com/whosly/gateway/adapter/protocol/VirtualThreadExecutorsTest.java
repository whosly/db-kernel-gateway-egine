package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Ensures the reflective virtual-thread helper is safe on JDK 17 (no throw on probe)
 * and that callers can always obtain a usable executor via the fallback path.
 */
class VirtualThreadExecutorsTest {

    @Test
    void probeDoesNotThrowOnCurrentJdk() {
        assertThatCode(VirtualThreadExecutors::isAvailable).doesNotThrowAnyException();
        assertThatCode(() -> VirtualThreadExecutors.namedVirtualThreadFactory("vt-test-"))
                .doesNotThrowAnyException();
        assertThatCode(() -> VirtualThreadExecutors.newVirtualThreadPerTaskExecutor("vt-test-"))
                .doesNotThrowAnyException();
        assertThatCode(() -> VirtualThreadExecutors.newVirtualThreadPerTaskExecutor("vt-test-", 0))
                .doesNotThrowAnyException();
    }

    @Test
    void virtualOrFallbackProvidesExecutableExecutorOnJdk17() throws Exception {
        AtomicBoolean ran = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        ExecutorService executor = VirtualThreadExecutors.virtualOrFallback(
                true,
                "vt-fallback-",
                () -> java.util.concurrent.Executors.newFixedThreadPool(1, runnable -> {
                    Thread t = new Thread(runnable, "vt-platform-fallback");
                    t.setDaemon(true);
                    return t;
                }));

        try {
            executor.execute(() -> {
                ran.set(true);
                done.countDown();
            });
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ran).isTrue();
        } finally {
            executor.shutdownNow();
        }

        // On JDK 17 the reflective APIs are absent, so Optional.empty is expected.
        // On JDK 21+ they resolve; either way the helper must not throw.
        Optional<ExecutorService> maybe = VirtualThreadExecutors.newVirtualThreadPerTaskExecutor("probe-");
        if (!VirtualThreadExecutors.isAvailable()) {
            assertThat(maybe).isEmpty();
        } else {
            assertThat(maybe).isPresent();
            maybe.get().shutdownNow();
        }
    }

    @Test
    void disabledVirtualThreadsAlwaysUsesFallback() throws Exception {
        AtomicBoolean usedFallback = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        ExecutorService executor = VirtualThreadExecutors.virtualOrFallback(
                false,
                "ignored-",
                () -> {
                    usedFallback.set(true);
                    return java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "forced-fallback");
                        t.setDaemon(true);
                        return t;
                    });
                });

        try {
            assertThat(usedFallback).isTrue();
            executor.execute(done::countDown);
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }
}
