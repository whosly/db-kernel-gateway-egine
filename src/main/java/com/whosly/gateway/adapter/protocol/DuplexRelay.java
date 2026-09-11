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
 *
 * <p>Bytes are forwarded unchanged unless the traffic pipeline explicitly
 * rewrites a message, in which case the replacement payload is written instead.
 * When the pipeline denies an operation the relay answers the client with a
 * protocol-native error before closing (see {@link TrafficAction#DENY}); without
 * a configured responder it degrades to a plain close so the relay never
 * silently drops a denial.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class DuplexRelay {

    private static final Logger log = LoggerFactory.getLogger(DuplexRelay.class);
    private static final int DEFAULT_BUFFER_SIZE = 16 * 1024;

    private final String sessionId;
    private final int bufferSize;
    private final TrafficInspector trafficInspector;
    private final ProtocolErrorResponder errorResponder;

    public DuplexRelay(String sessionId) {
        this(sessionId, DEFAULT_BUFFER_SIZE);
    }

    public DuplexRelay(String sessionId, int bufferSize) {
        this(sessionId, bufferSize, TrafficInspector.passThrough());
    }

    public DuplexRelay(String sessionId, TrafficInspector trafficInspector) {
        this(sessionId, DEFAULT_BUFFER_SIZE, trafficInspector, null);
    }

    public DuplexRelay(String sessionId, int bufferSize, TrafficInspector trafficInspector) {
        this(sessionId, bufferSize, trafficInspector, null);
    }

    public DuplexRelay(String sessionId, TrafficInspector trafficInspector,
                       ProtocolErrorResponder errorResponder) {
        this(sessionId, DEFAULT_BUFFER_SIZE, trafficInspector, errorResponder);
    }

    public DuplexRelay(String sessionId, int bufferSize, TrafficInspector trafficInspector,
                       ProtocolErrorResponder errorResponder) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive");
        }
        this.bufferSize = bufferSize;
        this.trafficInspector = Objects.requireNonNull(trafficInspector, "trafficInspector must not be null");
        this.errorResponder = errorResponder;
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
                copy(TrafficDirection.CLIENT_TO_TARGET, "client->target", clientSocket, targetSocket,
                        clientSocket, firstDirectionDone, failure));
        Future<?> targetToClient = executorService.submit(() ->
                copy(TrafficDirection.TARGET_TO_CLIENT, "target->client", targetSocket, clientSocket,
                        clientSocket, firstDirectionDone, failure));

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

    private void copy(TrafficDirection trafficDirection, String direction, Socket source, Socket destination,
                      Socket clientSocket, CountDownLatch firstDirectionDone, AtomicReference<IOException> failure) {
        byte[] buffer = new byte[bufferSize];
        try {
            InputStream inputStream = source.getInputStream();
            OutputStream outputStream = destination.getOutputStream();
            int count;
            while ((count = inputStream.read(buffer)) >= 0) {
                WireMessage message = RawBackedMessage.of(trafficDirection, buffer, 0, count);
                TrafficDecision decision = trafficInspector.inspect(message);
                if (decision.action() == TrafficAction.DENY) {
                    log.warn("Relay {} denied by traffic inspector for session {}", direction, sessionId);
                    sendDenial(clientSocket);
                    break;
                }
                if (decision.action() == TrafficAction.CLOSE) {
                    log.debug("Relay {} closed by traffic inspector for session {}", direction, sessionId);
                    break;
                }
                write(outputStream, message, decision);
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

    /**
     * Writes the decided payload to the destination.
     *
     * <p>Without a rewrite the bytes that arrived are written straight from the
     * read buffer: no copy and no re-encoding. Only a mutated message is written
     * from its replacement, which is the invariant that keeps transparent
     * forwarding byte exact (rule 2.10).</p>
     */
    private static void write(OutputStream outputStream, WireMessage received, TrafficDecision decision)
            throws IOException {
        WireMessage outgoing = decision.message();
        if (outgoing.mutated()) {
            outputStream.write(outgoing.outputBytes(), outgoing.outputOffset(), outgoing.outputLength());
        } else {
            outputStream.write(received.originalBytes(), received.originalOffset(), received.originalLength());
        }
        outputStream.flush();
    }

    /**
     * Answers a denied operation with a protocol-native error. The denial is
     * always evaluated on the client direction, so the response goes back on the
     * client socket.
     */
    private void sendDenial(Socket clientSocket) {
        if (errorResponder == null) {
            log.warn("No protocol error responder configured for session {}; closing without response", sessionId);
            return;
        }

        try {
            errorResponder.respond(clientSocket.getOutputStream(),
                    new GatewayException(GatewayErrorMapping.RISK_DENIED));
        } catch (IOException e) {
            log.debug("Failed to send denial message for session {}: {}", sessionId, e.getMessage());
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
