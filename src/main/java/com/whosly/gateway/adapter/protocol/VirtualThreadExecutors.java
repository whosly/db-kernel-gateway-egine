package com.whosly.gateway.adapter.protocol;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Reflective access to JDK 21+ virtual-thread executors.
 *
 * <p>Compiles against {@code --release 17}: there are no direct references to
 * {@code Thread.ofVirtual()} or {@code Executors.newThreadPerTaskExecutor}.
 * On JDK 21+ the handles resolve and virtual-thread executors are returned;
 * on JDK 17 (and any JVM without those APIs) callers receive {@link Optional#empty()}
 * and should fall back to platform thread pools.</p>
 */
public final class VirtualThreadExecutors {

    private static final MethodHandle OF_VIRTUAL;
    private static final MethodHandle BUILDER_FACTORY;
    private static final MethodHandle BUILDER_NAME_COUNTER;
    private static final MethodHandle NEW_THREAD_PER_TASK_EXECUTOR;
    private static final boolean AVAILABLE;

    static {
        MethodHandle ofVirtual = null;
        MethodHandle builderFactory = null;
        MethodHandle builderNameCounter = null;
        MethodHandle newThreadPerTask = null;
        boolean available = false;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> ofVirtualClass = Class.forName("java.lang.Thread$Builder$OfVirtual");
            Class<?> builderClass = Class.forName("java.lang.Thread$Builder");
            ofVirtual = lookup.findStatic(
                    Thread.class, "ofVirtual", MethodType.methodType(ofVirtualClass));
            builderFactory = lookup.findVirtual(
                    builderClass, "factory", MethodType.methodType(ThreadFactory.class));
            builderNameCounter = lookup.findVirtual(
                    ofVirtualClass, "name",
                    MethodType.methodType(ofVirtualClass, String.class, long.class));
            newThreadPerTask = lookup.findStatic(
                    java.util.concurrent.Executors.class,
                    "newThreadPerTaskExecutor",
                    MethodType.methodType(ExecutorService.class, ThreadFactory.class));
            available = true;
        } catch (ClassNotFoundException | IllegalAccessException | NoSuchMethodException | RuntimeException ignored) {
            // JDK 17 or API unavailable — leave handles null.
        }
        OF_VIRTUAL = ofVirtual;
        BUILDER_FACTORY = builderFactory;
        BUILDER_NAME_COUNTER = builderNameCounter;
        NEW_THREAD_PER_TASK_EXECUTOR = newThreadPerTask;
        AVAILABLE = available;
    }

    private VirtualThreadExecutors() {
    }

    /** Whether the reflective virtual-thread APIs resolved on this JVM. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * Creates a thread-per-task executor whose virtual threads are named {@code prefix + n}
     * (1-based counter), or empty if virtual threads are unavailable.
     *
     * @param prefix name prefix; may be {@code null} or blank for un-renamed virtual threads
     */
    public static Optional<ExecutorService> newVirtualThreadPerTaskExecutor(String prefix) {
        if (!AVAILABLE) {
            return Optional.empty();
        }
        try {
            Optional<ThreadFactory> factory = namedVirtualThreadFactory(prefix);
            if (factory.isEmpty()) {
                return Optional.empty();
            }
            ExecutorService executor = (ExecutorService) NEW_THREAD_PER_TASK_EXECUTOR.invoke(factory.get());
            return Optional.of(executor);
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    /**
     * Creates a thread-per-task executor with {@code Thread.ofVirtual().name(prefix, start).factory()}
     * semantics when available.
     */
    public static Optional<ExecutorService> newVirtualThreadPerTaskExecutor(String prefix, long start) {
        if (!AVAILABLE || prefix == null) {
            return Optional.empty();
        }
        try {
            Object builder = OF_VIRTUAL.invoke();
            builder = BUILDER_NAME_COUNTER.invoke(builder, prefix, start);
            ThreadFactory factory = (ThreadFactory) BUILDER_FACTORY.invoke(builder);
            ExecutorService executor = (ExecutorService) NEW_THREAD_PER_TASK_EXECUTOR.invoke(factory);
            return Optional.of(executor);
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    /**
     * Returns a {@link ThreadFactory} that creates named virtual threads, or empty if unavailable.
     * When {@code prefix} is null/blank, uses the bare virtual-thread factory.
     */
    public static Optional<ThreadFactory> namedVirtualThreadFactory(String prefix) {
        if (!AVAILABLE) {
            return Optional.empty();
        }
        try {
            Object builder = OF_VIRTUAL.invoke();
            ThreadFactory virtualFactory = (ThreadFactory) BUILDER_FACTORY.invoke(builder);
            if (prefix == null || prefix.isEmpty()) {
                return Optional.of(virtualFactory);
            }
            AtomicInteger counter = new AtomicInteger();
            String namePrefix = prefix;
            return Optional.of(runnable -> {
                Thread thread = virtualFactory.newThread(runnable);
                thread.setName(namePrefix + counter.incrementAndGet());
                return thread;
            });
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    /**
     * When {@code enabled} and virtual threads are available, returns a virtual-thread executor;
     * otherwise invokes {@code fallback}.
     */
    public static ExecutorService virtualOrFallback(boolean enabled, String namePrefix,
                                                    Supplier<ExecutorService> fallback) {
        if (enabled) {
            Optional<ExecutorService> virtual = newVirtualThreadPerTaskExecutor(namePrefix);
            if (virtual.isPresent()) {
                return virtual.get();
            }
        }
        return fallback.get();
    }
}
