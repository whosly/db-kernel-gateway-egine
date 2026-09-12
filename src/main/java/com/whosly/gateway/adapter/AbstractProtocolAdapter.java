package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.protocol.BackendProvider;
import com.whosly.gateway.adapter.protocol.ClientAddressPolicy;
import com.whosly.gateway.adapter.protocol.DatabaseRiskPolicy;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficObserver;
import com.whosly.gateway.adapter.protocol.DirectBackendProvider;
import com.whosly.gateway.adapter.protocol.ProtocolSession;
import com.whosly.gateway.adapter.protocol.RewriteLimits;
import com.whosly.gateway.parser.SqlParser;
import com.whosly.gateway.service.DatabaseConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
 * 抽象协议适配器基类
 * 
 * 提供协议适配器的通用实现，具体的协议适配器可以继承此类
 *
 * <p>连接生命周期按生产要求收敛：有界的连接线程池、连接数上限、可配置的空闲超时、
 * 客户端地址白名单，以及停机时强制关闭在途连接并等待线程池退出。</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public abstract class AbstractProtocolAdapter implements ProtocolAdapter {

    protected static final Logger log = LoggerFactory.getLogger(AbstractProtocolAdapter.class);

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final int DEFAULT_MAX_CONNECTIONS = 200;
    /** Connect timeout applied when opening a target database connection. */
    protected static final int TARGET_CONNECT_TIMEOUT_MILLIS = 10_000;

    protected volatile boolean running = false;
    protected ServerSocket serverSocket;
    /** Bounded pool that runs client connection handlers. */
    protected ExecutorService executorService;
    /** Dedicated thread that only accepts client connections. */
    private ExecutorService acceptorExecutor;
    protected SqlParser sqlParser;
    protected DatabaseConnectionService databaseConnectionService;
    protected DatabaseTrafficObserver databaseTrafficObserver = DatabaseTrafficObserver.noop();
    protected DatabaseRiskPolicy databaseRiskPolicy = DatabaseRiskPolicy.allowAll();
    /** Bounds applied when a rewrite needs a whole message; never used otherwise. */
    protected RewriteLimits rewriteLimits = RewriteLimits.defaults();
    protected int port;
    protected String protocolName;
    private final Map<String, ProtocolSession> activeSessions = new ConcurrentHashMap<>();
    private final Set<Socket> activeClientSockets = ConcurrentHashMap.newKeySet();
    private Semaphore connectionPermits = new Semaphore(DEFAULT_MAX_CONNECTIONS);
    private int maxConnections = DEFAULT_MAX_CONNECTIONS;
    private int idleTimeoutMillis;
    private ClientAddressPolicy clientAddressPolicy = ClientAddressPolicy.allowAll();

    // 目标数据库配置
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

    /**
     * 创建SQL解析器
     * 
     * @return SQL解析器实例
     */
    protected abstract SqlParser createSqlParser();

    public void setPort(int port) {
        this.port = port;
    }

    public int getPort() {
        return this.port;
    }

    /**
     * Maximum number of concurrently proxied client connections. Must be positive.
     */
    public void setMaxConnections(int maxConnections) {
        if (maxConnections > 0) {
            this.maxConnections = maxConnections;
        }
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public int getActiveConnectionCount() {
        return activeClientSockets.size();
    }

    /**
     * Idle timeout applied to client sockets. {@code <= 0} disables the timeout.
     */
    public void setIdleTimeoutSeconds(long idleTimeoutSeconds) {
        this.idleTimeoutMillis = idleTimeoutSeconds > 0
                ? (int) Math.min(idleTimeoutSeconds * 1000L, Integer.MAX_VALUE)
                : 0;
    }

    public int getIdleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    public void setClientAddressPolicy(ClientAddressPolicy clientAddressPolicy) {
        this.clientAddressPolicy = clientAddressPolicy != null
                ? clientAddressPolicy
                : ClientAddressPolicy.allowAll();
    }

    /**
     * Bounds on assembling one whole message for a rewrite. Defaults to 1 MiB and
     * one second; only a rewrite that needs whole messages can ever reach them.
     */
    public void setRewriteLimits(RewriteLimits rewriteLimits) {
        this.rewriteLimits = rewriteLimits != null ? rewriteLimits : RewriteLimits.defaults();
    }

    public RewriteLimits getRewriteLimits() {
        return rewriteLimits;
    }

    // 目标数据库配置的setter方法
    public void setTargetHost(String targetHost) {
        this.targetHost = targetHost;
    }

    public void setTargetPort(int targetPort) {
        this.targetPort = targetPort;
    }

    public void setTargetUsername(String targetUsername) {
        this.targetUsername = targetUsername;
    }

    public void setTargetPassword(String targetPassword) {
        this.targetPassword = targetPassword;
    }

    public void setTargetDatabase(String targetDatabase) {
        this.targetDatabase = targetDatabase;
    }

    public void setDatabaseTrafficObserver(DatabaseTrafficObserver databaseTrafficObserver) {
        this.databaseTrafficObserver = databaseTrafficObserver != null ? databaseTrafficObserver : DatabaseTrafficObserver.noop();
    }

    public void setDatabaseRiskPolicy(DatabaseRiskPolicy databaseRiskPolicy) {
        this.databaseRiskPolicy = databaseRiskPolicy != null ? databaseRiskPolicy : DatabaseRiskPolicy.allowAll();
    }

    @Override
    public String getProtocolName() {
        return protocolName;
    }

    @Override
    public int getDefaultPort() {
        return port;
    }

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
            executorService = Executors.newFixedThreadPool(maxConnections,
                    namedThreadFactory("db-gateway-" + protocolName + "-conn-"));
            acceptorExecutor = Executors.newSingleThreadExecutor(
                    namedThreadFactory("db-gateway-" + protocolName + "-accept-"));
            running = true;

            log.info("Starting {} protocol adapter on port {} (max connections {})",
                    protocolName, port, maxConnections);
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
        if (acceptorExecutor != null) {
            acceptorExecutor.shutdownNow();
        }

        // Closing in-flight client sockets unblocks the relay reads, which then
        // close the matching target sockets and let the handler threads exit.
        activeClientSockets.forEach(AbstractProtocolAdapter::closeQuietly);
        activeClientSockets.clear();

        activeSessions.values().forEach(ProtocolSession::close);
        activeSessions.clear();

        shutdownExecutor(executorService);
        log.info("{} protocol adapter stopped successfully", protocolName);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public Collection<ProtocolSession> getActiveSessions() {
        return List.copyOf(activeSessions.values());
    }

    /**
     * Registers a per-connection session so it can be inspected while active.
     */
    protected void registerSession(ProtocolSession session) {
        activeSessions.put(session.getConnectionId(), session);
    }

    /**
     * Removes a session from the active set. Safe to call more than once.
     */
    protected void unregisterSession(ProtocolSession session) {
        if (session != null) {
            activeSessions.remove(session.getConnectionId());
        }
    }

    /**
     * Answers a connection rejected before any protocol exchange (connection
     * limit or access policy). Protocols that can send a native error override
     * this; the default closes the socket.
     */
    protected void rejectClientConnection(Socket clientSocket) {
        closeQuietly(clientSocket);
    }

    /**
     * Accept client connections and dispatch them to the bounded handler pool.
     */
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
                closeQuietly(clientSocket);
                continue;
            }

            if (!connectionPermits.tryAcquire()) {
                log.warn("Rejected connection from {}: connection limit {} reached",
                        clientSocket.getRemoteSocketAddress(), maxConnections);
                rejectClientConnection(clientSocket);
                continue;
            }

            configureClientSocket(clientSocket);
            activeClientSockets.add(clientSocket);
            executorService.submit(() -> handleSafely(clientSocket));
        }
    }

    private void handleSafely(Socket clientSocket) {
        try {
            handleClientConnection(clientSocket);
        } catch (RuntimeException e) {
            log.error("Unexpected error handling client connection {}",
                    clientSocket.getRemoteSocketAddress(), e);
        } finally {
            activeClientSockets.remove(clientSocket);
            connectionPermits.release();
        }
    }

    private void configureClientSocket(Socket clientSocket) {
        try {
            clientSocket.setTcpNoDelay(true);
            clientSocket.setKeepAlive(true);
            if (idleTimeoutMillis > 0) {
                clientSocket.setSoTimeout(idleTimeoutMillis);
            }
        } catch (IOException e) {
            log.debug("Failed to configure client socket {}: {}",
                    clientSocket.getRemoteSocketAddress(), e.getMessage());
        }
    }

    private void shutdownExecutor(ExecutorService executor) {
        if (executor == null) {
            return;
        }
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

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closing.
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closing.
        }
    }

    /**
     * Target connection strategy for one client session.
     *
     * <p>Today every session opens its own connection and releases (closes) it
     * when the session ends. A pooled implementation replaces this method, and
     * before handing a connection to another client it must require
     * {@link com.whosly.gateway.adapter.protocol.SessionSnapshot#isReusableWithoutReset()}
     * and apply an explicit reset strategy, otherwise the connection must be
     * destroyed (rule 8.3).</p>
     *
     * @return provider that supplies the target connection for a session
     */
    protected BackendProvider createBackendProvider() {
        return new DirectBackendProvider(targetHost, targetPort, TARGET_CONNECT_TIMEOUT_MILLIS);
    }

    /**
     * Handle a client connection with full protocol support.
     * 
     * @param clientSocket the client socket
     */
    protected abstract void handleClientConnection(java.net.Socket clientSocket);
}
