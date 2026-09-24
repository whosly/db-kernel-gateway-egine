package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.protocol.BackendEndpoint;
import com.whosly.gateway.adapter.protocol.BackendProvider;
import com.whosly.gateway.adapter.protocol.SessionSnapshot;
import com.whosly.gateway.adapter.protocol.PooledBackendProvider;
import com.whosly.gateway.adapter.protocol.ClientTlsTerminator;
import com.whosly.gateway.adapter.protocol.BackendSessionReset;
import com.whosly.gateway.adapter.protocol.ClientAddressPolicy;
import com.whosly.gateway.adapter.protocol.DatabaseRiskPolicy;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficObserver;
import com.whosly.gateway.adapter.protocol.DirectBackendProvider;
import com.whosly.gateway.adapter.protocol.FailoverBackendProvider;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.adapter.protocol.ProtocolSession;
import com.whosly.gateway.adapter.protocol.RewriteLimits;
import com.whosly.gateway.adapter.protocol.RoutingBackendProvider;
import com.whosly.gateway.adapter.protocol.RoutingRule;
import com.whosly.gateway.adapter.protocol.VirtualThreadExecutors;
import com.whosly.gateway.adapter.protocol.WeightedEndpoint;
import com.whosly.gateway.adapter.protocol.WeightedFailoverBackendProvider;
import com.whosly.gateway.masking.MaskingEngine;
import com.whosly.gateway.parser.SqlParser;
import com.whosly.gateway.service.DatabaseConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract protocol adapter base with connection governance, virtual threads,
 * backend failover / optional routing, and runtime metrics (P0).
 */
public abstract class AbstractProtocolAdapter implements ProtocolAdapter {

    protected static final Logger log = LoggerFactory.getLogger(AbstractProtocolAdapter.class);

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final int DEFAULT_MAX_CONNECTIONS = 200;
    protected static final int TARGET_CONNECT_TIMEOUT_MILLIS = 10_000;

    protected volatile boolean running = false;
    protected ServerSocket serverSocket;
    protected ExecutorService executorService;
    private ExecutorService acceptorExecutor;
    protected SqlParser sqlParser;
    /**
     * Legacy JDBC helper placeholder — <b>unused on the wire path</b>.
     * Relay uses {@link BackendProvider}; see {@link DatabaseConnectionService}.
     *
     * @deprecated Do not use for proxy sessions; retained for binary compatibility.
     */
    @Deprecated(since = "1.0.0", forRemoval = false)
    protected DatabaseConnectionService databaseConnectionService;
    protected DatabaseTrafficObserver databaseTrafficObserver = DatabaseTrafficObserver.noop();
    protected DatabaseRiskPolicy databaseRiskPolicy = DatabaseRiskPolicy.allowAll();
    protected RewriteLimits rewriteLimits = RewriteLimits.defaults();
    protected MaskingEngine maskingEngine = MaskingEngine.inactive();
    protected int port;
    protected String protocolName;
    private final Map<String, ProtocolSession> activeSessions = new ConcurrentHashMap<>();
    private final Set<Socket> activeClientSockets = ConcurrentHashMap.newKeySet();
    private Semaphore connectionPermits = new Semaphore(DEFAULT_MAX_CONNECTIONS);
    private int maxConnections = DEFAULT_MAX_CONNECTIONS;
    private int idleTimeoutMillis;
    private ClientAddressPolicy clientAddressPolicy = ClientAddressPolicy.allowAll();
    private boolean virtualThreadsEnabled = true;
    private boolean requireCleartextInspection = false;
    private GatewayRuntimeMetrics runtimeMetrics = new GatewayRuntimeMetrics();
    private List<BackendEndpoint> backendEndpoints = List.of();
    /** When true, {@link #backendProvider()} wraps the factory in {@link PooledBackendProvider}. */
    private boolean poolEnabled = false;
    private int poolMaxIdle = 8;
    private BackendSessionReset backendSessionReset = BackendSessionReset.none();
    private ClientTlsTerminator clientTlsTerminator = ClientTlsTerminator.disabled();
    /** When true, wrap providers in {@link RoutingBackendProvider} (Routing → Pool → Failover). */
    private boolean routingEnabled = false;
    private List<RoutingRule> routingRules = List.of();
    /** Shared across client sessions so pooling actually reuses sockets. */
    private volatile BackendProvider sharedBackendProvider;


    protected String targetHost = "localhost";
    protected int targetPort = 3306;
    protected String targetUsername = "";
    protected String targetPassword = "";
    protected String targetDatabase = "";

    public AbstractProtocolAdapter(String protocolName, int defaultPort) {
        this.protocolName = protocolName;
        this.port = defaultPort;
        this.sqlParser = createSqlParser();
        this.databaseConnectionService = new DatabaseConnectionService();
    }

    protected abstract SqlParser createSqlParser();

    public void setPort(int port) { this.port = port; }
    public int getPort() { return this.port; }

    public void setMaxConnections(int maxConnections) {
        if (maxConnections > 0) this.maxConnections = maxConnections;
    }
    public int getMaxConnections() { return maxConnections; }
    public int getActiveConnectionCount() { return activeClientSockets.size(); }

    public void setIdleTimeoutSeconds(long idleTimeoutSeconds) {
        this.idleTimeoutMillis = idleTimeoutSeconds > 0
                ? (int) Math.min(idleTimeoutSeconds * 1000L, Integer.MAX_VALUE) : 0;
    }
    public int getIdleTimeoutMillis() { return idleTimeoutMillis; }

    public void setClientAddressPolicy(ClientAddressPolicy clientAddressPolicy) {
        this.clientAddressPolicy = clientAddressPolicy != null ? clientAddressPolicy : ClientAddressPolicy.allowAll();
    }

    public void setRewriteLimits(RewriteLimits rewriteLimits) {
        this.rewriteLimits = rewriteLimits != null ? rewriteLimits : RewriteLimits.defaults();
    }
    public RewriteLimits getRewriteLimits() { return rewriteLimits; }

    public void setMaskingEngine(MaskingEngine maskingEngine) {
        this.maskingEngine = maskingEngine != null ? maskingEngine : MaskingEngine.inactive();
    }
    public MaskingEngine getMaskingEngine() { return maskingEngine; }

    public void setTargetHost(String targetHost) { this.targetHost = targetHost; }
    public void setTargetPort(int targetPort) { this.targetPort = targetPort; }
    public void setTargetUsername(String targetUsername) { this.targetUsername = targetUsername; }
    public void setTargetPassword(String targetPassword) { this.targetPassword = targetPassword; }
    public void setTargetDatabase(String targetDatabase) { this.targetDatabase = targetDatabase; }

    public void setDatabaseTrafficObserver(DatabaseTrafficObserver databaseTrafficObserver) {
        this.databaseTrafficObserver = databaseTrafficObserver != null ? databaseTrafficObserver : DatabaseTrafficObserver.noop();
    }
    public void setDatabaseRiskPolicy(DatabaseRiskPolicy databaseRiskPolicy) {
        this.databaseRiskPolicy = databaseRiskPolicy != null ? databaseRiskPolicy : DatabaseRiskPolicy.allowAll();
    }

    public void setVirtualThreadsEnabled(boolean virtualThreadsEnabled) {
        this.virtualThreadsEnabled = virtualThreadsEnabled;
    }
    public boolean isVirtualThreadsEnabled() { return virtualThreadsEnabled; }
    public void setRequireCleartextInspection(boolean requireCleartextInspection) {
        this.requireCleartextInspection = requireCleartextInspection;
    }
    public boolean isRequireCleartextInspection() { return requireCleartextInspection; }
    public void setRuntimeMetrics(GatewayRuntimeMetrics runtimeMetrics) {
        this.runtimeMetrics = runtimeMetrics != null ? runtimeMetrics : GatewayRuntimeMetrics.noop();
    }
    public GatewayRuntimeMetrics getRuntimeMetrics() { return runtimeMetrics; }
    public void setBackendEndpoints(List<BackendEndpoint> backendEndpoints) {
        this.backendEndpoints = backendEndpoints == null || backendEndpoints.isEmpty()
                ? List.of() : List.copyOf(backendEndpoints);
    }
    public List<BackendEndpoint> getBackendEndpoints() { return backendEndpoints; }

    public void setPoolEnabled(boolean poolEnabled) { this.poolEnabled = poolEnabled; }
    public boolean isPoolEnabled() { return poolEnabled; }
    public void setPoolMaxIdle(int poolMaxIdle) {
        if (poolMaxIdle < 0) {
            throw new IllegalArgumentException("poolMaxIdle must not be negative");
        }
        this.poolMaxIdle = poolMaxIdle;
    }
    public int getPoolMaxIdle() { return poolMaxIdle; }
    public void setBackendSessionReset(BackendSessionReset backendSessionReset) {
        this.backendSessionReset = backendSessionReset != null ? backendSessionReset : BackendSessionReset.none();
    }
    public BackendSessionReset getBackendSessionReset() { return backendSessionReset; }
    public void setClientTlsTerminator(ClientTlsTerminator clientTlsTerminator) {
        this.clientTlsTerminator = clientTlsTerminator != null ? clientTlsTerminator : ClientTlsTerminator.disabled();
    }
    public ClientTlsTerminator getClientTlsTerminator() { return clientTlsTerminator; }
    public boolean isClientTlsTerminateEnabled() { return clientTlsTerminator.isEnabled(); }

    public void setRoutingEnabled(boolean routingEnabled) { this.routingEnabled = routingEnabled; }
    public boolean isRoutingEnabled() { return routingEnabled; }
    public void setRoutingRules(List<RoutingRule> routingRules) {
        this.routingRules = routingRules == null || routingRules.isEmpty()
                ? List.of() : List.copyOf(routingRules);
    }
    public List<RoutingRule> getRoutingRules() { return routingRules; }


    @Override public String getProtocolName() { return protocolName; }
    @Override public int getDefaultPort() { return port; }

    @Override
    public void start() {
        if (running) {
            log.warn("{} protocol adapter is already running", protocolName);
            return;
        }
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            serverSocket = socket;
            connectionPermits = new Semaphore(maxConnections);
            executorService = createConnectionExecutor();
            acceptorExecutor = Executors.newSingleThreadExecutor(
                    namedThreadFactory("db-gateway-" + protocolName + "-accept-"));
            running = true;
            log.info("Starting {} protocol adapter on port {} (max connections {}, virtualThreads={}, pool={}, routing={}, tlsTerminate={}, backends={})",
                    protocolName, port, maxConnections, virtualThreadsEnabled, poolEnabled,
                    routingEnabled && !routingRules.isEmpty(),
                    clientTlsTerminator.isEnabled(),
                    backendEndpoints.isEmpty() ? targetHost + ":" + targetPort : backendEndpoints);
            acceptorExecutor.submit(this::acceptConnections);
            log.info("{} protocol adapter started successfully", protocolName);
        } catch (IOException e) {
            log.error("Failed to start {} protocol adapter", protocolName, e);
            running = false;
        }
    }

    @Override
    public void stop() {
        if (!running) {
            log.warn("{} protocol adapter is not running", protocolName);
            return;
        }
        log.info("Stopping {} protocol adapter", protocolName);
        running = false;
        closeQuietly(serverSocket);
        if (acceptorExecutor != null) acceptorExecutor.shutdownNow();
        activeClientSockets.forEach(AbstractProtocolAdapter::closeQuietly);
        activeClientSockets.clear();
        activeSessions.values().forEach(ProtocolSession::close);
        activeSessions.clear();
        closeSharedBackendProvider();
        shutdownExecutor(executorService);
        log.info("{} protocol adapter stopped successfully", protocolName);
    }

    @Override public boolean isRunning() { return running; }
    @Override public Collection<ProtocolSession> getActiveSessions() { return List.copyOf(activeSessions.values()); }

    protected void registerSession(ProtocolSession session) {
        activeSessions.put(session.getConnectionId(), session);
    }
    protected void unregisterSession(ProtocolSession session) {
        if (session != null) activeSessions.remove(session.getConnectionId());
    }
    protected void rejectClientConnection(Socket clientSocket) { closeQuietly(clientSocket); }

    protected void acceptConnections() {
        while (running && !serverSocket.isClosed()) {
            Socket clientSocket;
            try {
                clientSocket = serverSocket.accept();
            } catch (IOException e) {
                if (running && serverSocket != null && !serverSocket.isClosed()) {
                    log.error("Error accepting client connection", e);
                }
                continue;
            }
            if (!clientAddressPolicy.isAllowed(clientSocket.getInetAddress())) {
                log.warn("Rejected connection from {} by client access policy",
                        clientSocket.getRemoteSocketAddress());
                runtimeMetrics.recordConnectionRejectedPolicy();
                closeQuietly(clientSocket);
                continue;
            }
            if (!connectionPermits.tryAcquire()) {
                log.warn("Rejected connection from {}: connection limit {} reached",
                        clientSocket.getRemoteSocketAddress(), maxConnections);
                runtimeMetrics.recordConnectionRejectedLimit();
                rejectClientConnection(clientSocket);
                continue;
            }
            configureClientSocket(clientSocket);
            activeClientSockets.add(clientSocket);
            runtimeMetrics.recordConnectionAccepted();
            executorService.submit(() -> handleSafely(clientSocket));
        }
    }

    private void handleSafely(Socket clientSocket) {
        Socket streamSocket = clientSocket;
        try {
            streamSocket = clientTlsTerminator.wrapAcceptedClient(clientSocket);
            if (streamSocket != clientSocket) {
                activeClientSockets.add(streamSocket);
            }
            handleClientConnection(streamSocket);
        } catch (IOException e) {
            log.warn("Client TLS handshake / wrap failed for {}: {}",
                    clientSocket.getRemoteSocketAddress(), e.getMessage());
            closeQuietly(streamSocket);
        } catch (RuntimeException e) {
            log.error("Unexpected error handling client connection {}",
                    clientSocket.getRemoteSocketAddress(), e);
        } finally {
            activeClientSockets.remove(clientSocket);
            if (streamSocket != clientSocket) {
                activeClientSockets.remove(streamSocket);
            }
            connectionPermits.release();
        }
    }

    private void configureClientSocket(Socket clientSocket) {
        try {
            clientSocket.setTcpNoDelay(true);
            clientSocket.setKeepAlive(true);
            if (idleTimeoutMillis > 0) clientSocket.setSoTimeout(idleTimeoutMillis);
        } catch (IOException e) {
            log.debug("Failed to configure client socket {}: {}",
                    clientSocket.getRemoteSocketAddress(), e.getMessage());
        }
    }

    private void shutdownExecutor(ExecutorService executor) {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private ExecutorService createConnectionExecutor() {
        String prefix = "db-gateway-" + protocolName + "-conn-";
        if (virtualThreadsEnabled) {
            return VirtualThreadExecutors.virtualOrFallback(true, prefix, () -> {
                log.warn("Virtual threads unavailable, falling back to fixed pool");
                return Executors.newFixedThreadPool(maxConnections, namedThreadFactory(prefix));
            });
        }
        return Executors.newFixedThreadPool(maxConnections, namedThreadFactory(prefix));
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) {}
    }
    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) {}
    }

    /**
     * Shared backend provider for all client sessions of this adapter.
     * Pooling only works when the same provider instance is reused.
     */
    protected BackendProvider backendProvider() {
        BackendProvider existing = sharedBackendProvider;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (sharedBackendProvider == null) {
                sharedBackendProvider = buildBackendProvider();
            }
            return sharedBackendProvider;
        }
    }

    /**
     * @deprecated Prefer {@link #backendProvider()} so pooling can share idle sockets.
     *             Retained for subclasses/tests that still call the old name; now
     *             delegates to the shared instance.
     */
    protected BackendProvider createBackendProvider() {
        return backendProvider();
    }

    /**
     * Builds {@code Routing → Pool → Failover/Fixed} when routing is enabled;
     * otherwise {@code Pool → Failover/Fixed} (unchanged default).
     */
    private BackendProvider buildBackendProvider() {
        if (poolEnabled) {
            log.info("{} backend pool enabled (maxIdle={}, reset={})",
                    protocolName, poolMaxIdle,
                    backendSessionReset == BackendSessionReset.NONE ? "none/close-if-unsafe" : "protocol/custom");
        }
        BackendProvider fallback = maybePool(createUnpooledBackendProvider(resolveBackendEndpoints()));
        if (!routingEnabled || routingRules.isEmpty()) {
            return fallback;
        }
        RoutingBackendProvider.Builder builder = new RoutingBackendProvider.Builder().fallback(fallback);
        for (RoutingRule rule : routingRules) {
            List<BackendEndpoint> plain = new ArrayList<>(rule.endpoints().size());
            for (WeightedEndpoint weighted : rule.endpoints()) {
                plain.add(weighted.endpoint());
            }
            BackendProvider routeFactory = createUnpooledBackendProvider(plain, rule.endpoints());
            builder.rule(rule, maybePool(routeFactory));
        }
        log.info("{} backend routing enabled ({} rule(s))", protocolName, routingRules.size());
        return builder.build();
    }

    private BackendProvider maybePool(BackendProvider factory) {
        if (!poolEnabled) {
            return factory;
        }
        return new PooledBackendProvider(factory, poolMaxIdle, backendSessionReset);
    }

    /** Direct or failover factory without pooling — used as the pool's opener. */
    protected BackendProvider createUnpooledBackendProvider() {
        return createUnpooledBackendProvider(resolveBackendEndpoints());
    }

    private BackendProvider createUnpooledBackendProvider(List<BackendEndpoint> endpoints) {
        return createUnpooledBackendProvider(endpoints, null);
    }

    /**
     * When {@code weighted} is non-null and any weight differs from 1, use
     * {@link WeightedFailoverBackendProvider}; otherwise Direct or ordered Failover.
     */
    private BackendProvider createUnpooledBackendProvider(List<BackendEndpoint> endpoints,
                                                          List<WeightedEndpoint> weighted) {
        if (weighted != null && hasNonDefaultWeight(weighted)) {
            return new WeightedFailoverBackendProvider(weighted, TARGET_CONNECT_TIMEOUT_MILLIS, runtimeMetrics);
        }
        if (endpoints.size() == 1) {
            BackendEndpoint only = endpoints.get(0);
            return new DirectBackendProvider(only.host(), only.port(), TARGET_CONNECT_TIMEOUT_MILLIS);
        }
        return new FailoverBackendProvider(endpoints, TARGET_CONNECT_TIMEOUT_MILLIS, runtimeMetrics);
    }

    private static boolean hasNonDefaultWeight(List<WeightedEndpoint> weighted) {
        for (WeightedEndpoint endpoint : weighted) {
            if (endpoint.weight() != 1) {
                return true;
            }
        }
        return false;
    }

    private void closeSharedBackendProvider() {
        BackendProvider provider = sharedBackendProvider;
        sharedBackendProvider = null;
        if (provider instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Releases a backend socket with session observation so a pooled provider can
     * decide reuse. Snapshot should be taken <em>before</em> {@link ProtocolSession#close()}.
     */
    protected void releaseBackend(BackendProvider provider, Socket connection, ProtocolSession session) {
        if (provider == null) {
            return;
        }
        SessionSnapshot snapshot = session != null ? session.snapshot() : null;
        provider.release(connection, snapshot);
    }

    private List<BackendEndpoint> resolveBackendEndpoints() {
        if (!backendEndpoints.isEmpty()) return backendEndpoints;
        return List.of(new BackendEndpoint(targetHost, targetPort));
    }

    protected abstract void handleClientConnection(java.net.Socket clientSocket);
}
