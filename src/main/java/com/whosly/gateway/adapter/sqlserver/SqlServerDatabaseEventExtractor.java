package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.MessageBounder;
import com.whosly.gateway.adapter.protocol.ProtocolConnectionState;
import com.whosly.gateway.adapter.protocol.TrafficDirection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * P1-lite TDS observation: Login7 identity labels + SQL_BATCH statements.
 *
 * <p>Bytes are never rewritten — the extractor only reads cleartext TDS frames
 * that {@link TdsMessageFraming} can bound. Encrypted tunnels after PreLogin
 * simply yield no events (fail-open observation). Password bytes in Login7 are
 * never decoded into events.</p>
 */
public class SqlServerDatabaseEventExtractor {

    private static final Logger log = LoggerFactory.getLogger(SqlServerDatabaseEventExtractor.class);

    private final String protocolName;
    private final String sessionId;
    private final SqlServerSession session;
    private final ByteArrayOutputStream pendingClient = new ByteArrayOutputStream();
    private boolean loginObserved;
    private boolean opaqueTunnel;

    public SqlServerDatabaseEventExtractor(String protocolName, String sessionId, SqlServerSession session) {
        this.protocolName = protocolName;
        this.sessionId = sessionId;
        this.session = session;
    }

    public boolean isOpaqueTunnel() {
        return opaqueTunnel;
    }

    public MessageBounder messageBounder(TrafficDirection direction) {
        if (opaqueTunnel) {
            return (bytes, offset, length) -> null;
        }
        return TdsMessageFraming::completeMessageEnds;
    }

    public List<DatabaseTrafficEvent> inspect(TrafficDirection direction, byte[] bytes, int offset, int length) {
        if (opaqueTunnel || bytes == null || length <= 0) {
            return List.of();
        }
        if (direction != TrafficDirection.CLIENT_TO_TARGET) {
            // P1-lite: no backend token decode yet (ERROR token deferred).
            return List.of();
        }
        pendingClient.write(bytes, offset, length);
        byte[] buffered = pendingClient.toByteArray();
        int[] ends = TdsMessageFraming.completeMessageEnds(buffered, 0, buffered.length);
        if (ends.length == 0) {
            return List.of();
        }
        List<DatabaseTrafficEvent> events = new ArrayList<>();
        int consumed = 0;
        for (int end : ends) {
            events.addAll(observeClientMessage(buffered, consumed, end - consumed));
            consumed = end;
        }
        if (consumed > 0) {
            pendingClient.reset();
            if (consumed < buffered.length) {
                pendingClient.write(buffered, consumed, buffered.length - consumed);
            }
        }
        return events;
    }

    private List<DatabaseTrafficEvent> observeClientMessage(byte[] bytes, int offset, int length) {
        if (length < TdsFrameCodec.HEADER_LENGTH) {
            return List.of();
        }
        int typeCode = TdsFrameCodec.type(bytes, offset, offset + length);
        if (session != null) {
            session.setLastPacketType(typeCode);
        }
        TdsPacketType type = TdsPacketType.fromCode(typeCode);
        if (type == null) {
            return List.of();
        }
        byte[] payload = concatenatePayloads(bytes, offset, length);
        return switch (type) {
            case PRELOGIN -> {
                if (session != null) {
                    session.tryTransitionTo(ProtocolConnectionState.NEGOTIATING);
                }
                yield List.of();
            }
            case TDS7_LOGIN -> observeLogin7(payload);
            case SQL_BATCH -> observeSqlBatch(payload);
            default -> List.of();
        };
    }

    private List<DatabaseTrafficEvent> observeLogin7(byte[] payload) {
        if (loginObserved) {
            return List.of();
        }
        Optional<Login7Observation> parsed = Login7Observation.tryParse(payload);
        if (parsed.isEmpty()) {
            return List.of();
        }
        loginObserved = true;
        Login7Observation login = parsed.get();
        if (session != null) {
            session.tryTransitionTo(ProtocolConnectionState.AUTHENTICATING);
            login.username().ifPresent(user -> {
                session.setLoginUsername(user);
                session.putAttribute("client.user", user);
            });
            login.database().ifPresent(db -> {
                session.setInitialDatabase(db);
                session.putAttribute("client.database", db);
            });
            login.appName().ifPresent(app -> session.putAttribute("client.app", app));
            login.hostName().ifPresent(host -> session.putAttribute("client.host", host));
        }
        String label = login.username().orElse("(unknown)")
                + "@"
                + login.database().orElse("(default)");
        log.debug("SQL Server session {} Login7 observed as {}", sessionId, label);
        return List.of(DatabaseTrafficEvent.builder(protocolName, sessionId, "LOGIN7", label)
                .attribute("username", login.username().orElse(""))
                .attribute("database", login.database().orElse(""))
                .build());
    }

    private List<DatabaseTrafficEvent> observeSqlBatch(byte[] payload) {
        String sql = extractSqlBatchText(payload);
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        if (session != null) {
            session.tryTransitionTo(ProtocolConnectionState.READY);
            session.tryTransitionTo(ProtocolConnectionState.EXECUTING);
        }
        return List.of(DatabaseTrafficEvent.builder(protocolName, sessionId, "SQL_BATCH", sql).build());
    }

    /**
     * SQL_BATCH payload: optional ALL_HEADERS (TDS 7.2+) then UCS-2LE SQL text.
     */
    static String extractSqlBatchText(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }
        int sqlOffset = 0;
        if (payload.length >= 4) {
            int totalHeaderLen = u32le(payload, 0);
            if (totalHeaderLen >= 4 && totalHeaderLen < payload.length && (payload.length - totalHeaderLen) >= 2) {
                sqlOffset = totalHeaderLen;
            }
        }
        int sqlBytes = payload.length - sqlOffset;
        if (sqlBytes < 2 || (sqlBytes & 1) != 0) {
            // Odd trailing byte: still try floor length.
            sqlBytes = sqlBytes & ~1;
        }
        if (sqlBytes < 2) {
            return null;
        }
        String sql = new String(payload, sqlOffset, sqlBytes, StandardCharsets.UTF_16LE).trim();
        return sql.isEmpty() ? null : sql;
    }

    /** Concatenate TDS packet payloads for one logical message (EOM-delimited). */
    static byte[] concatenatePayloads(byte[] bytes, int offset, int length) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(0, length - TdsFrameCodec.HEADER_LENGTH));
        int endExclusive = offset + length;
        int cursor = offset;
        while (cursor + TdsFrameCodec.HEADER_LENGTH <= endExclusive) {
            int packetLength = TdsFrameCodec.packetLength(bytes, cursor, endExclusive);
            if (packetLength < TdsFrameCodec.MIN_PACKET_LENGTH || cursor + packetLength > endExclusive) {
                break;
            }
            byte[] part = TdsFrameCodec.payloadOf(
                    java.util.Arrays.copyOfRange(bytes, cursor, cursor + packetLength));
            out.write(part, 0, part.length);
            int status = TdsFrameCodec.status(bytes, cursor, endExclusive);
            cursor += packetLength;
            if (TdsPacketStatus.isEom(status)) {
                break;
            }
        }
        return out.toByteArray();
    }

    private static int u32le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }
}
