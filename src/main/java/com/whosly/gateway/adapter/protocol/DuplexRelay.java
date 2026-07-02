package com.whosly.gateway.adapter.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bidirectional byte relay for transparent database protocol proxying.
 */
public class DuplexRelay {

    private static final Logger log = LoggerFactory.getLogger(DuplexRelay.class);
    private static final int DEFAULT_BUFFER_SIZE = 16 * 1024;

    private final String sessionId;
    private final int bufferSize;

    public DuplexRelay(String sessionId) {
        this(sessionId, DEFAULT_BUFFER_SIZE);
    }

    public DuplexRelay(String sessionId, int bufferSize) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive");
        }
        this.bufferSize = bufferSize;
    }

    public void relay(Socket clientSocket, Socket targetSocket) throws IOException {
        Objects.requireNonNull(clientSocket, "clientSocket must not be null");
        Objects.requireNonNull(targetSocket, "targetSocket must not be null");

        CountDownLatch firstDirectionDone = new CountDownLatch(1);
        AtomicReference<IOException> failure = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "duplex-relay-" + sessionId);
            thread.setDaemon(true);
            return thread;
        });

        Future<?> clientToTarget = executorService.submit(() ->
                copy("client->target", clientSocket, targetSocket, firstDirectionDone, failure));
        Future<?> targetToClient = executorService.submit(() ->
                copy("target->client", targetSocket, clientSocket, firstDirectionDone, failure));

        try {
            firstDirectionDone.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while relaying database traffic", e);
        } finally {
            closeQuietly(clientSocket);
            closeQuietly(targetSocket);
            waitForCompletion(clientToTarget);
            waitForCompletion(targetToClient);
            executorService.shutdownNow();
        }

        IOException ioException = failure.get();
        if (ioException != null) {
            throw ioException;
        }
    }

    private void copy(String direction, Socket source, Socket destination,
                      CountDownLatch firstDirectionDone, AtomicReference<IOException> failure) {
        byte[] buffer = new byte[bufferSize];
        try {
            InputStream inputStream = source.getInputStream();
            OutputStream outputStream = destination.getOutputStream();
            int count;
            while ((count = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, count);
                outputStream.flush();
            }
        } catch (IOException e) {
            if (!source.isClosed() && !destination.isClosed()) {
                failure.compareAndSet(null, e);
                log.debug("Relay {} failed for session {}: {}", direction, sessionId, e.getMessage());
            }
        } finally {
            firstDirectionDone.countDown();
            closeQuietly(source);
            closeQuietly(destination);
        }
    }

    private static void waitForCompletion(Future<?> future) {
        try {
            future.get(500, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            future.cancel(true);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closing.
        }
    }
}
