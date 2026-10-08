package com.whosly.gateway.adapter.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
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
 * <p>Forwarding never waits for observation, so by default every read is written
 * as it arrives. The single exception is a rewrite that needs a whole message: the
 * pipeline then reports a {@link MessageBounder} for that direction and the relay
 * assembles messages before inspecting them, holding the unfinished tail. That hold
 * is bounded by {@link RewriteLimits}, and exceeding either bound rejects the
 * session with a protocol-native error instead of forwarding bytes the gateway
 * could not rewrite (rule 8.1).</p>
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
    /** Bounds on assembling a message; {@code null} disables message assembly. */
    private final RewriteLimits rewriteLimits;

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

    public DuplexRelay(String sessionId, TrafficInspector trafficInspector,
                       ProtocolErrorResponder errorResponder, RewriteLimits rewriteLimits) {
        this(sessionId, DEFAULT_BUFFER_SIZE, trafficInspector, errorResponder, rewriteLimits);
    }

    public DuplexRelay(String sessionId, int bufferSize, TrafficInspector trafficInspector,
                       ProtocolErrorResponder errorResponder) {
        this(sessionId, bufferSize, trafficInspector, errorResponder, null);
    }

    public DuplexRelay(String sessionId, int bufferSize, TrafficInspector trafficInspector,
                       ProtocolErrorResponder errorResponder, RewriteLimits rewriteLimits) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive");
        }
        this.bufferSize = bufferSize;
        this.trafficInspector = Objects.requireNonNull(trafficInspector, "trafficInspector must not be null");
        this.errorResponder = errorResponder;
        this.rewriteLimits = rewriteLimits;
    }

    public void relay(Socket clientSocket, Socket targetSocket) throws IOException {
        relay(clientSocket, targetSocket, null);
    }

    /**
     * Relays both directions. When {@code clientInputPrefix} is non-empty, those
     * exact bytes are observed and forwarded on client→target <em>before</em>
     * reading further from the client socket — used after an early handshake
     * peek for routing so transparency is preserved.
     */
    public void relay(Socket clientSocket, Socket targetSocket, byte[] clientInputPrefix) throws IOException {
        Objects.requireNonNull(clientSocket, "clientSocket must not be null");
        Objects.requireNonNull(targetSocket, "targetSocket must not be null");
        byte[] prefix = clientInputPrefix == null || clientInputPrefix.length == 0
                ? null : clientInputPrefix;

        CountDownLatch firstDirectionDone = new CountDownLatch(1);
        AtomicReference<IOException> failure = new AtomicReference<>();
        ExecutorService executorService = newRelayExecutor(sessionId);

        Future<?> clientToTarget = executorService.submit(() ->
                copy(TrafficDirection.CLIENT_TO_TARGET, "client->target", clientSocket, targetSocket,
                        clientSocket, firstDirectionDone, failure, prefix));
        Future<?> targetToClient = executorService.submit(() ->
                copy(TrafficDirection.TARGET_TO_CLIENT, "target->client", targetSocket, clientSocket,
                        clientSocket, firstDirectionDone, failure, null));

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
                      Socket clientSocket, CountDownLatch firstDirectionDone, AtomicReference<IOException> failure,
                      byte[] inputPrefix) {
        try {
            InputStream inputStream = source.getInputStream();
            if (inputPrefix != null && inputPrefix.length > 0) {
                inputStream = new SequenceInputStream(new ByteArrayInputStream(inputPrefix), inputStream);
            }
            OutputStream outputStream = destination.getOutputStream();
            MessageBounder messageBounder = rewriteLimits == null
                    ? null
                    : trafficInspector.messageBounder(trafficDirection);
            if (messageBounder == null) {
                copyImmediately(trafficDirection, direction, inputStream, outputStream, clientSocket);
            } else {
                copyWholeMessages(trafficDirection, direction, source, inputStream, outputStream, clientSocket,
                        messageBounder);
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

    private void copyImmediately(TrafficDirection trafficDirection, String direction, InputStream inputStream,
                                 OutputStream outputStream, Socket clientSocket) throws IOException {
        byte[] buffer = new byte[bufferSize];
        int count;
        while ((count = inputStream.read(buffer)) >= 0) {
            WireMessage message = RawBackedMessage.of(trafficDirection, buffer, 0, count);
            if (!dispatch(message, direction, outputStream, clientSocket)) {
                break;
            }
        }
    }

    private void copyWholeMessages(TrafficDirection trafficDirection, String direction, Socket source,
                                   InputStream inputStream, OutputStream outputStream, Socket clientSocket,
                                   MessageBounder messageBounder) throws IOException {
        MessageHoldBuffer holdBuffer = new MessageHoldBuffer(messageBounder, rewriteLimits);
        int originalSoTimeout = source.getSoTimeout();
        byte[] buffer = new byte[bufferSize];
        boolean flushHeldTail = false;
        try {
            while (true) {
                int count;
                try {
                    applyHoldReadTimeout(source, holdBuffer, originalSoTimeout);
                    count = inputStream.read(buffer);
                } catch (SocketTimeoutException timeout) {
                    if (!holdBuffer.isHolding()) {
                        throw timeout;
                    }
                    if (rewriteLimits.exceedsHoldTime(holdBuffer.heldMillis())) {
                        rejectUnassembled(direction, clientSocket, holdBuffer);
                        break;
                    }
                    continue;
                }

                if (count < 0) {
                    flushHeldTail = true;
                    break;
                }
                if (count == 0) {
                    continue;
                }

                MessageHoldBuffer.HeldWindow window;
                try {
                    window = holdBuffer.append(buffer, 0, count);
                } catch (RewriteLimitException e) {
                    log.warn("Relay {} cannot assemble a message for session {}: {}",
                            direction, sessionId, e.getMessage());
                    sendGatewayError(clientSocket, GatewayErrorMapping.RESOURCE_EXHAUSTED);
                    break;
                }
                if (window.completeBytes() <= 0) {
                    continue;
                }

                boolean keepRelaying = true;
                int start = 0;
                for (int end : window.messageEnds()) {
                    WireMessage message =
                            RawBackedMessage.of(trafficDirection, window.bytes(), start, end - start);
                    if (!dispatch(message, direction, outputStream, clientSocket)) {
                        keepRelaying = false;
                        break;
                    }
                    start = end;
                }
                if (!keepRelaying) {
                    break;
                }
            }
        } finally {
            restoreSoTimeout(source, originalSoTimeout);
            if (flushHeldTail) {
                byte[] tail = holdBuffer.drain();
                if (tail.length > 0) {
                    outputStream.write(tail);
                    outputStream.flush();
                }
            }
        }
    }

    private void applyHoldReadTimeout(Socket source, MessageHoldBuffer holdBuffer, int originalSoTimeout)
            throws SocketException {
        if (!holdBuffer.isHolding()) {
            if (source.getSoTimeout() != originalSoTimeout) {
                source.setSoTimeout(originalSoTimeout);
            }
            return;
        }
        long remaining = rewriteLimits.maxHoldMillis() - holdBuffer.heldMillis();
        source.setSoTimeout((int) Math.max(1L, Math.min(remaining, Integer.MAX_VALUE)));
    }

    private static void restoreSoTimeout(Socket source, int originalSoTimeout) {
        try {
            if (source.getSoTimeout() != originalSoTimeout) {
                source.setSoTimeout(originalSoTimeout);
            }
        } catch (SocketException e) {
            log.debug("Could not restore socket timeout: {}", e.getMessage());
        }
    }

    private boolean dispatch(WireMessage message, String direction, OutputStream outputStream, Socket clientSocket)
            throws IOException {
        TrafficDecision decision = trafficInspector.inspect(message);
        if (decision.action() == TrafficAction.DENY) {
            log.warn("Relay {} denied by traffic inspector for session {}", direction, sessionId);
            sendGatewayError(clientSocket, GatewayErrorMapping.RISK_DENIED);
            return false;
        }
        if (decision.action() == TrafficAction.CLOSE) {
            log.debug("Relay {} closed by traffic inspector for session {}", direction, sessionId);
            return false;
        }
        write(outputStream, message, decision);
        return true;
    }

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

    private void rejectUnassembled(String direction, Socket clientSocket, MessageHoldBuffer holdBuffer) {
        log.warn("Relay {} held a partial message for {} ms for session {}; rejecting it instead of "
                        + "forwarding it unrewritten",
                direction, holdBuffer.heldMillis(), sessionId);
        sendGatewayError(clientSocket, GatewayErrorMapping.RESOURCE_EXHAUSTED);
    }

    private void sendGatewayError(Socket clientSocket, GatewayErrorMapping mapping) {
        if (errorResponder == null) {
            log.warn("No protocol error responder configured for session {}; closing without response", sessionId);
            return;
        }

        try {
            errorResponder.respond(clientSocket.getOutputStream(), new GatewayException(mapping));
        } catch (IOException e) {
            log.debug("Failed to send gateway error for session {}: {}", sessionId, e.getMessage());
        }
    }

    private static void waitForCompletion(Future<?> future) {
        try {
            future.get(500, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            future.cancel(true);
        }
    }


    private static ExecutorService newRelayExecutor(String sessionId) {
        return VirtualThreadExecutors.newVirtualThreadPerTaskExecutor(
                        "duplex-relay-" + sessionId + "-", 0)
                .orElseGet(() -> Executors.newFixedThreadPool(2, runnable -> {
                    Thread thread = new Thread(runnable, "duplex-relay-" + sessionId);
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closing.
        }
    }
}
