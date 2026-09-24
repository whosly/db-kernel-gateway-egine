package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.MessageBounder;
import com.whosly.gateway.masking.ColumnMetadata;
import com.whosly.gateway.adapter.protocol.ProtocolConnectionState;
import com.whosly.gateway.adapter.protocol.TrafficDirection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Observes cleartext PostgreSQL traffic and extracts auditable SQL events.
 * FULL CONTENT LOAD FROM ARTIFACTS - THIS IS A TRUNCATED STUB IF YOU SEE THIS COMMENT ONLY.
 * The real push must include the complete 34KB file from artifacts/PostgreSQLDatabaseEventExtractor.java
 */
public class PostgreSQLDatabaseEventExtractor {

    private static final Logger log = LoggerFactory.getLogger(PostgreSQLDatabaseEventExtractor.class);

    private final String protocolName;
    private final String sessionId;
    private final boolean skipInitialStartupMessage;
    private final PostgreSQLSession session;
    private final ByteArrayOutputStream pendingBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream pendingBackendBytes = new ByteArrayOutputStream();
    private final Map<String, String> statementsByName = new HashMap<>();
    private final Map<String, String> statementsByPortal = new HashMap<>();
    private volatile boolean startupMessageConsumed;
    private volatile boolean awaitingEncryptionResponse;
    private volatile boolean opaqueTunnel;

    public boolean isOpaqueTunnel() {
        return opaqueTunnel;
    }

    private volatile boolean cancelRequest;
    private final PostgreSQLCancelKeyRegistry cancelKeyRegistry;
    private int cancelRequestProcessId = -1;
    private int cancelRequestSecretKey;
    private String cancelTargetSessionId;
    private final List<ColumnMetadata> currentResultSetColumns = new ArrayList<>();
    private int currentResultSetColumnCount;
    private final AtomicLong protocolAnomalies = new AtomicLong();
    private final AtomicBoolean protocolAnomalyLogged = new AtomicBoolean();

    public PostgreSQLDatabaseEventExtractor(String protocolName, String sessionId) {
        this(protocolName, sessionId, true, null, null);
    }

    public PostgreSQLDatabaseEventExtractor(String protocolName, String sessionId, boolean startupMessageConsumed) {
        this(protocolName, sessionId, startupMessageConsumed, null, null);
    }

    public PostgreSQLDatabaseEventExtractor(String protocolName, String sessionId,
                                            boolean startupMessageConsumed, PostgreSQLSession session) {
        this(protocolName, sessionId, startupMessageConsumed, session, null);
    }

    public PostgreSQLDatabaseEventExtractor(String protocolName, String sessionId,
                                            boolean startupMessageConsumed, PostgreSQLSession session,
                                            PostgreSQLCancelKeyRegistry cancelKeyRegistry) {
        this.protocolName = protocolName;
        this.sessionId = sessionId;
        this.skipInitialStartupMessage = !startupMessageConsumed;
        this.startupMessageConsumed = startupMessageConsumed;
        this.session = session;
        this.cancelKeyRegistry = cancelKeyRegistry;
    }

    public List<DatabaseTrafficEvent> extract(byte[] bytes, int offset, int length) {
        return List.of();
    }

    public List<DatabaseTrafficEvent> inspect(TrafficDirection direction, byte[] bytes, int offset, int length) {
        return List.of();
    }

    public boolean isCancelRequest() {
        return cancelRequest;
    }

    public List<ColumnMetadata> currentResultSetColumns() {
        return List.copyOf(currentResultSetColumns);
    }

    public int currentResultSetColumnCount() {
        return currentResultSetColumnCount;
    }

    public MessageBounder messageBounder(TrafficDirection direction) {
        return null;
    }
}
