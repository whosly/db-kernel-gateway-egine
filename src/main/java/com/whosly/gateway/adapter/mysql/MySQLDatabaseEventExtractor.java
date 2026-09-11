package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.ProtocolConnectionState;
import com.whosly.gateway.adapter.protocol.TrafficDirection;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Observes cleartext MySQL traffic and extracts auditable SQL events.
 *
 * <p>The relay forwards the original bytes; this extractor only reads them. It
 * tracks the two directions independently:</p>
 * <ul>
 *   <li>client to target: connection phase (handshake response), then command
 *       phase (COM_QUERY / COM_STMT_PREPARE SQL and COM_INIT_DB database);</li>
 *   <li>target to client: the authentication OK that ends the connection phase,
 *       then the response packets needed to keep transaction state real.</li>
 * </ul>
 *
 * <p>Anything that cannot be parsed confidently is dropped rather than guessed.
 * A TLS or compression switch turns the session into an opaque tunnel where no
 * further inspection happens.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class MySQLDatabaseEventExtractor {

    // MySQL column/parameter type codes (protocol "enum_field_types").
    private static final int MYSQL_TYPE_DECIMAL = 0x00;
    private static final int MYSQL_TYPE_NULL = 0x06;
    private static final int MYSQL_TYPE_TINY = 0x01;
    private static final int MYSQL_TYPE_SHORT = 0x02;
    private static final int MYSQL_TYPE_LONG = 0x03;
    private static final int MYSQL_TYPE_FLOAT = 0x04;
    private static final int MYSQL_TYPE_DOUBLE = 0x05;
    private static final int MYSQL_TYPE_TIMESTAMP = 0x07;
    private static final int MYSQL_TYPE_LONGLONG = 0x08;
    private static final int MYSQL_TYPE_INT24 = 0x09;
    private static final int MYSQL_TYPE_DATE = 0x0a;
    private static final int MYSQL_TYPE_TIME = 0x0b;
    private static final int MYSQL_TYPE_DATETIME = 0x0c;
    private static final int MYSQL_TYPE_YEAR = 0x0d;
    private static final int MYSQL_TYPE_NEWDATE = 0x0e;
    private static final int MYSQL_TYPE_VARCHAR = 0x0f;
    private static final int MYSQL_TYPE_BIT = 0x10;
    private static final int MYSQL_TYPE_JSON = 0xf5;
    private static final int MYSQL_TYPE_NEWDECIMAL = 0xf6;
    private static final int MYSQL_TYPE_ENUM = 0xf7;
    private static final int MYSQL_TYPE_SET = 0xf8;
    private static final int MYSQL_TYPE_TINY_BLOB = 0xf9;
    private static final int MYSQL_TYPE_MEDIUM_BLOB = 0xfa;
    private static final int MYSQL_TYPE_LONG_BLOB = 0xfb;
    private static final int MYSQL_TYPE_BLOB = 0xfc;
    private static final int MYSQL_TYPE_VAR_STRING = 0xfd;
    private static final int MYSQL_TYPE_STRING = 0xfe;
    private static final int MYSQL_TYPE_GEOMETRY = 0xff;

    // The first payload byte identifies the packet kind.
    private static final int OK_PACKET_HEADER = 0x00;
    private static final int LOCAL_INFILE_HEADER = 0xFB;
    private static final int EOF_PACKET_HEADER = 0xFE;
    private static final int ERR_PACKET_HEADER = 0xFF;

    // Every client command packet starts at sequence 0.
    private static final int COMMAND_START_SEQUENCE = 0;

    // Minimum payload of a COM_STMT_PREPARE prepare-ok header with CLIENT_PROTOCOL_41.
    private static final int PREPARE_HEADER_LENGTH = 12;

    private static final Logger log = LoggerFactory.getLogger(MySQLDatabaseEventExtractor.class);

    private final String protocolName;
    private final String sessionId;
    /** Optional session used to publish observed protocol state; null in some tests. */
    private final MySQLSession session;
    /** True when construction starts before authentication, so the handshake is observed first. */
    private final boolean commandPhaseOnly;
    /** Buffer for client command packets. */
    private final ByteArrayOutputStream pendingBytes = new ByteArrayOutputStream();
    /** Buffer used while looking for the server authentication OK packet. */
    private final ByteArrayOutputStream pendingTargetBytes = new ByteArrayOutputStream();
    /** Buffer for the handshake response, which TCP may split across reads. */
    private final ByteArrayOutputStream pendingHandshakeBytes = new ByteArrayOutputStream();
    /** Accumulates payloads of a fragmented logical packet (payload == 2^24 - 1). */
    private final ByteArrayOutputStream pendingLogicalPayload = new ByteArrayOutputStream();
    /** Buffer for target response packets used for transaction-state observation. */
    private final ByteArrayOutputStream pendingServerBytes = new ByteArrayOutputStream();
    /** Set while reassembling a physically fragmented logical packet. */
    private boolean frameContinuation;
    /** True once the client may send commands (server authentication OK seen). */
    private boolean readyForCommands;
    /** Position inside the current server response; see {@link MySQLResponsePhase}. */
    private MySQLResponsePhase responsePhase = MySQLResponsePhase.IDLE;
    /** Column definition packets still to skip in the current result set. */
    private int remainingColumnDefinitions;
    /** True while the current response streams result set rows. */
    private boolean resultSetInProgress;
    /** True while the client uploads LOAD DATA LOCAL INFILE content. */
    private boolean localInfileInProgress;
    /** True after a TLS/compression switch: bytes become opaque and are not parsed. */
    private boolean opaqueTunnel;
    /** Client capability flags from the handshake response; they drive optional layouts. */
    private long clientCapabilityFlags;
    /** True once the first client packet (handshake response) has been parsed. */
    private boolean clientHandshakeResponseSeen;
    /** Shape the command in flight declared it would produce; null when none. */
    private MySQLResponseShape pendingResponseShape;
    /** True when response observation stopped and waits for the next command. */
    private boolean responseObservationSuspended;
    /** Column definitions counted while consuming a COM_FIELD_LIST response. */
    private int columnListCount;
    /** Parameter definition packets still expected in a COM_STMT_PREPARE response. */
    private int prepareParamDefinitions;
    /** Column definition packets still expected in a COM_STMT_PREPARE response. */
    private int prepareColumnDefinitions;
    /** Position inside a COM_STMT_PREPARE metadata tail. */
    private MySQLPreparePhase preparePhase = MySQLPreparePhase.HEADER;
    /** Parameter types of the previous COM_QUERY, reused when new_params_bound_flag is 0. */
    private int[] lastQueryAttributeTypes = new int[0];
    /** Count of sequence/malformed anomalies; forwarding is never affected by them. */
    private final AtomicLong protocolAnomalies = new AtomicLong();
    private final AtomicBoolean protocolAnomalyLogged = new AtomicBoolean();

    public MySQLDatabaseEventExtractor(String protocolName, String sessionId) {
        this(protocolName, sessionId, true, null);
    }

    public MySQLDatabaseEventExtractor(String protocolName, String sessionId, boolean readyForCommands) {
        this(protocolName, sessionId, readyForCommands, null);
    }

    public MySQLDatabaseEventExtractor(String protocolName, String sessionId,
                                       boolean readyForCommands, MySQLSession session) {
        this.protocolName = protocolName;
        this.sessionId = sessionId;
        this.commandPhaseOnly = !readyForCommands;
        this.readyForCommands = readyForCommands;
        this.session = session;
    }

    public List<DatabaseTrafficEvent> extract(byte[] bytes, int offset, int length) {
        return extractClientCommandBytes(bytes, offset, length);
    }

    public List<DatabaseTrafficEvent> inspect(TrafficDirection direction, byte[] bytes, int offset, int length) {
        if (opaqueTunnel) {
            return List.of();
        }

        if (direction == TrafficDirection.TARGET_TO_CLIENT) {
            if (readyForCommands) {
                observeCommandPhaseResponses(bytes, offset, length);
            } else {
                observeTargetBytes(bytes, offset, length);
            }
            return List.of();
        }

        if (commandPhaseOnly && !readyForCommands) {
            observeClientHandshakeBytes(bytes, offset, length);
            return List.of();
        }

        return extractClientCommandBytes(bytes, offset, length);
    }

    private void observeClientHandshakeBytes(byte[] bytes, int offset, int length) {
        if (clientHandshakeResponseSeen) {
            return;
        }

        /*
         * TCP can split the MySQL Handshake Response across reads. Capability
         * flags exist only in that first client packet; later client packets in
         * multi-round authentication are credential continuations and must not
         * be reinterpreted as capability flags.
         */
        pendingHandshakeBytes.write(bytes, offset, length);
        byte[] buffered = pendingHandshakeBytes.toByteArray();
        if (buffered.length < MySQLFrameCodec.HEADER_LENGTH + 4) {
            return;
        }

        int payloadLength = MySQLFrameCodec.payloadLength(buffered, 0, buffered.length);
        if (payloadLength < 4 || buffered.length < MySQLFrameCodec.HEADER_LENGTH + payloadLength) {
            return;
        }

        int capabilityOffset = MySQLFrameCodec.HEADER_LENGTH;
        long capabilityFlags = (buffered[capabilityOffset] & 0xFFL)
                | ((buffered[capabilityOffset + 1] & 0xFFL) << 8)
                | ((buffered[capabilityOffset + 2] & 0xFFL) << 16)
                | ((buffered[capabilityOffset + 3] & 0xFFL) << 24);
        clientHandshakeResponseSeen = true;
        pendingHandshakeBytes.reset();
        clientCapabilityFlags |= capabilityFlags;
        observeHandshakeResponse(buffered, payloadLength, capabilityFlags);
        /*
         * TLS, zlib and zstd cannot be parsed here: as soon as the client opts
         * into one of them the session becomes an opaque tunnel and inspection
         * stops, while byte forwarding continues unchanged.
         */
        if ((capabilityFlags & MySQLCapability.CLIENT_SSL.getFlag()) != 0
                || (capabilityFlags & MySQLCapability.CLIENT_COMPRESS.getFlag()) != 0
                || (capabilityFlags & MySQLCapability.CLIENT_ZSTD_COMPRESSION_ALGORITHM.getFlag()) != 0) {
            opaqueTunnel = true;
            pendingBytes.reset();
            pendingTargetBytes.reset();
            pendingHandshakeBytes.reset();
            pendingLogicalPayload.reset();
            pendingServerBytes.reset();
            frameContinuation = false;
            responsePhase = MySQLResponsePhase.IDLE;
            remainingColumnDefinitions = 0;
            resultSetInProgress = false;
            localInfileInProgress = false;
        }

        advanceSession(ProtocolConnectionState.NEGOTIATING);
        advanceSession(ProtocolConnectionState.AUTHENTICATING);
    }

    private List<DatabaseTrafficEvent> extractClientCommandBytes(byte[] bytes, int offset, int length) {
        if (localInfileInProgress) {
            /*
             * The client is uploading LOAD DATA LOCAL INFILE bytes rather than
             * sending commands. Skipping them prevents file content (which may
             * start with any byte, including 0x03) from being reported as SQL.
             * The upload ends when the server OK/ERR response arrives.
             */
            return List.of();
        }

        pendingBytes.write(bytes, offset, length);
        byte[] buffered = pendingBytes.toByteArray();
        int cursor = 0;
        List<DatabaseTrafficEvent> events = new ArrayList<>();

        while (buffered.length - cursor >= MySQLFrameCodec.HEADER_LENGTH) {
            int payloadLength = MySQLFrameCodec.payloadLength(buffered, cursor, buffered.length);
            if (payloadLength < 0) {
                break;
            }
            int packetLength = MySQLFrameCodec.HEADER_LENGTH + payloadLength;
            if (buffered.length - cursor < packetLength) {
                break;
            }

            int payloadOffset = cursor + MySQLFrameCodec.HEADER_LENGTH;
            if (frameContinuation) {
                appendLogicalPayload(buffered, payloadOffset, payloadLength);
                if (payloadLength < MySQLFrameCodec.MAX_PAYLOAD_LENGTH) {
                    dispatchLogicalPayload(events);
                }
            } else if (payloadLength == MySQLFrameCodec.MAX_PAYLOAD_LENGTH) {
                /*
                 * MySQL splits a logical packet whose payload reaches 2^24 - 1
                 * into a run of maximum-length packets terminated by a shorter
                 * one. Observation must reassemble the run before locating SQL.
                 */
                frameContinuation = true;
                appendLogicalPayload(buffered, payloadOffset, payloadLength);
            } else {
                int sequenceId = MySQLFrameCodec.sequenceId(buffered, cursor, buffered.length);
                if (payloadLength > 0 && sequenceId == COMMAND_START_SEQUENCE) {
                    /*
                     * A command always starts at sequence 0, which makes it the one
                     * observation boundary that stays trustworthy even when the
                     * previous response could not be interpreted (rule 2.10/3.2).
                     */
                    resyncObservation();
                } else if (payloadLength > 0) {
                    recordProtocolAnomaly("command packet sequence " + sequenceId + " (expected 0)");
                }
                extractPacket(buffered, payloadOffset, payloadLength).ifPresent(events::add);
            }
            cursor += packetLength;
        }

        compact(buffered, cursor);
        return events;
    }

    private void appendLogicalPayload(byte[] bytes, int offset, int length) {
        pendingLogicalPayload.write(bytes, offset, length);
    }

    private void dispatchLogicalPayload(List<DatabaseTrafficEvent> events) {
        byte[] logicalPayload = pendingLogicalPayload.toByteArray();
        pendingLogicalPayload.reset();
        frameContinuation = false;
        extractPacket(logicalPayload, 0, logicalPayload.length).ifPresent(events::add);
    }

    private void observeTargetBytes(byte[] bytes, int offset, int length) {
        if (!commandPhaseOnly || readyForCommands) {
            return;
        }

        /*
         * During authentication the only packet worth waiting for is the server
         * OK_Packet (first payload byte 0x00). ERR and auth-switch packets are
         * skipped until it arrives, so the client command phase is not entered
         * prematurely.
         */
        pendingTargetBytes.write(bytes, offset, length);
        byte[] buffered = pendingTargetBytes.toByteArray();
        int cursor = 0;

        while (buffered.length - cursor >= MySQLFrameCodec.HEADER_LENGTH + 1) {
            int payloadLength = MySQLFrameCodec.payloadLength(buffered, cursor, buffered.length);
            int packetLength = MySQLFrameCodec.HEADER_LENGTH + payloadLength;
            if (payloadLength <= 0 || buffered.length - cursor < packetLength) {
                break;
            }

            int firstPayloadByte = buffered[cursor + MySQLFrameCodec.HEADER_LENGTH] & 0xFF;
            if (firstPayloadByte == 0x00) {
                readyForCommands = true;
                advanceSession(ProtocolConnectionState.READY);
                pendingTargetBytes.reset();
                return;
            }
            cursor += packetLength;
        }

        pendingTargetBytes.reset();
        if (cursor < buffered.length) {
            pendingTargetBytes.write(buffered, cursor, buffered.length - cursor);
        }
    }

    private Optional<DatabaseTrafficEvent> extractPacket(byte[] packet, int payloadOffset, int payloadLength) {
        if (payloadLength < 1) {
            return Optional.empty();
        }

        int commandCode = packet[payloadOffset] & 0xFF;
        Optional<MySQLCommandType> commandType = MySQLCommandType.fromCode(commandCode);
        if (commandType.isEmpty()) {
            return Optional.empty();
        }

        MySQLCommandType command = commandType.get();
        if (command == MySQLCommandType.COM_QUIT) {
            // COM_QUIT ends the session; the server closes the connection.
            advanceSession(ProtocolConnectionState.CLOSING);
            return Optional.empty();
        }

        /*
         * Declare what the target will answer before observing anything (rule
         * 3.6). A command that draws no response must not move the session into
         * EXECUTING, otherwise it would stay there until some later command
         * finishes; an unrecognised shape suspends observation instead of guessing.
         */
        boolean expectsResponse = prepareResponseObservation(command);
        if (expectsResponse) {
            /*
             * A command cycle starts here: the session moves to EXECUTING and
             * returns to READY when the matching response completes (rule
             * 2.1/2.5). State is observation only, so an illegal transition is
             * ignored, never raised.
             */
            advanceSession(ProtocolConnectionState.EXECUTING);
        }

        // COM_RESET_CONNECTION clears session state without re-authenticating.
        if (command == MySQLCommandType.COM_RESET_CONNECTION) {
            if (session != null) {
                session.resetObservedState();
            }
            return Optional.empty();
        }
        // COM_CHANGE_USER re-authenticates on the same connection; the target
        // database performs it, the gateway mirrors the resulting identity.
        if (command == MySQLCommandType.COM_CHANGE_USER) {
            observeChangeUser(packet, payloadOffset + 1, payloadOffset + payloadLength);
            return Optional.empty();
        }
        // COM_STMT_CLOSE drops one prepared statement and draws no response.
        if (command == MySQLCommandType.COM_STMT_CLOSE) {
            if (session != null) {
                session.markPreparedStatementClosed();
            }
            return Optional.empty();
        }
        // COM_INIT_DB changes session state instead of producing a SQL event.
        if (command == MySQLCommandType.COM_INIT_DB) {
            observeInitDb(packet, payloadOffset + 1, payloadOffset + payloadLength);
            return Optional.empty();
        }

        // Only text SQL and prepared-statement SQL are audited for now.
        if (!expectsResponse
                || (command != MySQLCommandType.COM_QUERY && command != MySQLCommandType.COM_STMT_PREPARE)) {
            return Optional.empty();
        }

        int sqlOffset = payloadOffset + 1;
        if (command == MySQLCommandType.COM_QUERY && hasCapability(MySQLCapability.CLIENT_QUERY_ATTRIBUTES)) {
            // SQL text is prefixed with parameter metadata; step over it first.
            sqlOffset = skipQueryAttributes(packet, sqlOffset, payloadOffset + payloadLength);
        }
        if (sqlOffset > payloadOffset + payloadLength) {
            return Optional.empty();
        }

        String sql = new String(packet, sqlOffset, payloadOffset + payloadLength - sqlOffset, StandardCharsets.UTF_8);
        if (sql.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(DatabaseTrafficEvent.builder(protocolName, sessionId, command.name(), sql).build());
    }

    private boolean hasCapability(MySQLCapability capability) {
        return (clientCapabilityFlags & capability.getFlag()) != 0;
    }

    private int skipQueryAttributes(byte[] bytes, int offset, int endExclusive) {
        /*
         * COM_QUERY with CLIENT_QUERY_ATTRIBUTES prefixes the SQL text with:
         * parameter_count, parameter_set_count, null bitmap, new-params flag,
         * per-parameter type/name metadata, then binary parameter values.
         * The gateway only needs the SQL boundary here. Parameter values may
         * contain sensitive data, so this method intentionally skips them.
         */
        LengthEncodedInteger parameterCount = readLengthEncodedInteger(bytes, offset, endExclusive);
        LengthEncodedInteger parameterSetCount = readLengthEncodedInteger(bytes, parameterCount.nextOffset(), endExclusive);
        int cursor = parameterSetCount.nextOffset();
        long count = parameterCount.value();
        long setCount = parameterSetCount.value();
        if (count <= 0) {
            return cursor;
        }

        int nullBitmapLength = (int) ((count + 7) / 8);
        if (cursor + nullBitmapLength > endExclusive) {
            return endExclusive + 1;
        }

        int nullBitmapOffset = cursor;
        cursor += nullBitmapLength;
        if (cursor >= endExclusive) {
            return endExclusive + 1;
        }

        int newParamsBoundFlag = bytes[cursor++] & 0xFF;
        int[] parameterTypes = new int[(int) count];
        if (newParamsBoundFlag != 0) {
            for (int index = 0; index < count; index++) {
                if (cursor + 2 > endExclusive) {
                    return endExclusive + 1;
                }
                parameterTypes[index] = bytes[cursor] & 0xFF;
                cursor += 2;
                LengthEncodedInteger nameLength = readLengthEncodedInteger(bytes, cursor, endExclusive);
                cursor = nameLength.nextOffset() + safeLongToInt(nameLength.value());
                if (cursor > endExclusive) {
                    return endExclusive + 1;
                }
            }
            lastQueryAttributeTypes = parameterTypes.clone();
        } else if (lastQueryAttributeTypes.length == count) {
            /*
             * MySQL can send new_params_bound_flag=0 to reuse the previous
             * query attribute type metadata on this connection. Keep that
             * state per extractor/session so we can still locate the SQL text
             * without guessing parameter sizes.
             */
            parameterTypes = lastQueryAttributeTypes.clone();
        } else {
            return endExclusive + 1;
        }

        for (long setIndex = 0; setIndex < setCount; setIndex++) {
            for (int parameterIndex = 0; parameterIndex < count; parameterIndex++) {
                if (isNullParameter(bytes, nullBitmapOffset, parameterIndex)) {
                    continue;
                }
                cursor = skipBinaryProtocolValue(bytes, cursor, endExclusive, parameterTypes[parameterIndex]);
                if (cursor > endExclusive) {
                    return endExclusive + 1;
                }
            }
        }

        return cursor;
    }

    private static boolean isNullParameter(byte[] bytes, int nullBitmapOffset, int parameterIndex) {
        int nullByte = bytes[nullBitmapOffset + (parameterIndex / 8)] & 0xFF;
        return (nullByte & (1 << (parameterIndex % 8))) != 0;
    }

    private static int skipBinaryProtocolValue(byte[] bytes, int offset, int endExclusive, int type) {
        return switch (type) {
            case MYSQL_TYPE_NULL -> offset;
            case MYSQL_TYPE_TINY -> offset + 1;
            case MYSQL_TYPE_SHORT, MYSQL_TYPE_YEAR -> offset + 2;
            case MYSQL_TYPE_LONG, MYSQL_TYPE_INT24, MYSQL_TYPE_FLOAT -> offset + 4;
            case MYSQL_TYPE_LONGLONG, MYSQL_TYPE_DOUBLE -> offset + 8;
            case MYSQL_TYPE_DATE, MYSQL_TYPE_TIME, MYSQL_TYPE_DATETIME, MYSQL_TYPE_TIMESTAMP, MYSQL_TYPE_NEWDATE ->
                    skipLengthEncodedBytes(bytes, offset, endExclusive);
            case MYSQL_TYPE_DECIMAL, MYSQL_TYPE_NEWDECIMAL, MYSQL_TYPE_VARCHAR, MYSQL_TYPE_BIT, MYSQL_TYPE_JSON,
                    MYSQL_TYPE_ENUM, MYSQL_TYPE_SET, MYSQL_TYPE_TINY_BLOB, MYSQL_TYPE_MEDIUM_BLOB,
                    MYSQL_TYPE_LONG_BLOB, MYSQL_TYPE_BLOB, MYSQL_TYPE_VAR_STRING, MYSQL_TYPE_STRING,
                    MYSQL_TYPE_GEOMETRY -> skipLengthEncodedBytes(bytes, offset, endExclusive);
            default -> skipLengthEncodedBytes(bytes, offset, endExclusive);
        };
    }

    private static int skipLengthEncodedBytes(byte[] bytes, int offset, int endExclusive) {
        LengthEncodedInteger length = readLengthEncodedInteger(bytes, offset, endExclusive);
        return length.nextOffset() + safeLongToInt(length.value());
    }

    /**
     * Reads a MySQL length-encoded integer.
     *
     * <p>{@code 0xFB} denotes NULL and is reported as value 0; it is only valid
     * inside result rows, not in the lengths this observer reads.</p>
     */
    private static LengthEncodedInteger readLengthEncodedInteger(byte[] bytes, int offset, int endExclusive) {
        if (offset >= endExclusive) {
            return new LengthEncodedInteger(0, endExclusive + 1);
        }

        int first = bytes[offset] & 0xFF;
        if (first < 0xFB) {
            return new LengthEncodedInteger(first, offset + 1);
        }
        if (first == 0xFC) {
            return new LengthEncodedInteger(readLittleEndian(bytes, offset + 1, 2, endExclusive), offset + 3);
        }
        if (first == 0xFD) {
            return new LengthEncodedInteger(readLittleEndian(bytes, offset + 1, 3, endExclusive), offset + 4);
        }
        if (first == 0xFE) {
            return new LengthEncodedInteger(readLittleEndian(bytes, offset + 1, 8, endExclusive), offset + 9);
        }
        return new LengthEncodedInteger(0, offset + 1);
    }

    private static long readLittleEndian(byte[] bytes, int offset, int length, int endExclusive) {
        if (offset + length > endExclusive) {
            return 0;
        }

        long value = 0;
        for (int index = 0; index < length; index++) {
            value |= (bytes[offset + index] & 0xFFL) << (8 * index);
        }
        return value;
    }

    private static int safeLongToInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) value;
    }

    private void observeCommandPhaseResponses(byte[] bytes, int offset, int length) {
        /*
         * While observation is suspended nothing is interpreted, and the buffer is
         * dropped so an unattributable response can neither accumulate nor be
         * mistaken for the next command's answer (rule 2.10).
         */
        if (responseObservationSuspended) {
            pendingServerBytes.reset();
            return;
        }

        /*
         * Packets are consumed strictly in order and interpreted through the
         * response phase machine. Basing the decision on the packet order (not
         * on a single sequence id) is what allows the EOF that follows column
         * definitions to be told apart from the result set terminator.
         */
        pendingServerBytes.write(bytes, offset, length);
        byte[] buffered = pendingServerBytes.toByteArray();
        int cursor = 0;

        while (buffered.length - cursor >= MySQLFrameCodec.HEADER_LENGTH) {
            int payloadLength = MySQLFrameCodec.payloadLength(buffered, cursor, buffered.length);
            if (payloadLength < 0) {
                break;
            }
            int packetLength = MySQLFrameCodec.HEADER_LENGTH + payloadLength;
            if (buffered.length - cursor < packetLength) {
                break;
            }

            if (payloadLength > 0) {
                /*
                 * Server response packets are intentionally NOT sequence-validated:
                 * a multi-statement response legitimately restarts or continues the
                 * sequence at points the observer cannot infer from the response
                 * side alone, so validating there would only produce false alarms.
                 * Command-side sequences are validated where the rule is exact.
                 */
                processResponsePacket(buffered, cursor + MySQLFrameCodec.HEADER_LENGTH, payloadLength);
            }
            cursor += packetLength;
        }

        compactServer(buffered, cursor);
    }

    /**
     * Advances the response phase machine by one server packet.
     */
    private void processResponsePacket(byte[] packet, int payloadOffset, int payloadLength) {
        if (payloadLength <= 0) {
            return;
        }

        int firstByte = packet[payloadOffset] & 0xFF;
        switch (responsePhase) {
            case IDLE, RESPONSE_HEADER -> handleResponseHeader(packet, payloadOffset, payloadLength, firstByte);
            case COLUMN_DEFINITIONS -> {
                remainingColumnDefinitions--;
                if (remainingColumnDefinitions <= 0) {
                    responsePhase = hasCapability(MySQLCapability.CLIENT_DEPRECATE_EOF)
                            ? MySQLResponsePhase.ROWS
                            : MySQLResponsePhase.COLUMN_TERMINATOR;
                }
            }
            case COLUMN_TERMINATOR -> responsePhase = MySQLResponsePhase.ROWS;
            case COLUMN_LIST -> consumeColumnList(packet, payloadOffset, payloadLength, firstByte);
            case PREPARE -> handlePrepareResponse(packet, payloadOffset, payloadLength, firstByte);
            case ROWS -> {
                if (firstByte == EOF_PACKET_HEADER) {
                    observeResultSetTerminator(packet, payloadOffset, payloadLength);
                } else if (resultSetInProgress && session != null) {
                    session.incrementResultSetRows();
                }
            }
        }
    }

    private void handleResponseHeader(byte[] packet, int payloadOffset, int payloadLength, int firstByte) {
        if (firstByte == ERR_PACKET_HEADER) {
            // ERR is valid for every command that expects a response.
            observeErrPacket(packet, payloadOffset, payloadLength);
            completeResponse();
            finishLocalInfile();
            return;
        }

        MySQLResponseShape shape = pendingResponseShape;
        if (shape == null) {
            // No command declared these bytes, so their meaning is unknown.
            suspendObservation("response packet with no declared command shape");
            return;
        }

        switch (shape) {
            case OK -> {
                if (firstByte == OK_PACKET_HEADER) {
                    applyOkPacket(packet, payloadOffset, payloadLength);
                } else {
                    suspendObservation("expected an OK packet, got 0x" + Integer.toHexString(firstByte));
                }
            }
            case EOF_ONLY -> {
                /*
                 * Deprecated commands answer with EOF, or with OK when the client
                 * negotiated CLIENT_DEPRECATE_EOF.
                 */
                if (firstByte == EOF_PACKET_HEADER) {
                    observeResultSetTerminator(packet, payloadOffset, payloadLength);
                } else if (firstByte == OK_PACKET_HEADER) {
                    applyOkPacket(packet, payloadOffset, payloadLength);
                } else {
                    suspendObservation("expected EOF or OK, got 0x" + Integer.toHexString(firstByte));
                }
            }
            case RAW_STRING -> {
                /*
                 * COM_STATISTICS answers with a single unmarked string packet. It
                 * carries no metadata worth keeping, so the command ends here.
                 */
                completeResponse();
            }
            case COLUMN_LIST -> {
                columnListCount = 0;
                consumeColumnList(packet, payloadOffset, payloadLength, firstByte);
            }
            case PREPARE -> handlePrepareResponse(packet, payloadOffset, payloadLength, firstByte);
            case RESULTSET -> handleResultSetHeader(packet, payloadOffset, payloadLength, firstByte);
            default -> suspendObservation("unsupported response shape " + shape);
        }
    }

    /**
     * Handles the first packet of a {@code RESULTSET} response.
     *
     * <p>A statement that returns no rows legitimately answers with an OK_Packet
     * instead of a column count, and {@code LOAD DATA} answers with a LOCAL INFILE
     * request.</p>
     */
    private void handleResultSetHeader(byte[] packet, int payloadOffset, int payloadLength, int firstByte) {
        if (firstByte == OK_PACKET_HEADER) {
            applyOkPacket(packet, payloadOffset, payloadLength);
            return;
        }
        if (firstByte == LOCAL_INFILE_HEADER) {
            // LOAD DATA LOCAL INFILE: the server asks the client to upload a file.
            localInfileInProgress = true;
            responsePhase = MySQLResponsePhase.IDLE;
            resultSetInProgress = false;
            advanceSession(ProtocolConnectionState.STREAMING);
            return;
        }

        LengthEncodedInteger columnCount =
                readLengthEncodedInteger(packet, payloadOffset, payloadOffset + payloadLength);
        remainingColumnDefinitions = safeLongToInt(columnCount.value());
        resultSetInProgress = remainingColumnDefinitions > 0;
        if (resultSetInProgress && session != null) {
            // Result set metadata observation (rule 2.6).
            session.beginResultSet(remainingColumnDefinitions);
        }
        responsePhase = resultSetInProgress
                ? MySQLResponsePhase.COLUMN_DEFINITIONS
                : MySQLResponsePhase.ROWS;
    }

    /**
     * Consumes one packet of a {@code COM_FIELD_LIST} response.
     *
     * <p>This response carries no column count packet: it is a run of column
     * definitions ended by EOF, or by OK when EOF is deprecated. Reading its first
     * definition as a column count is exactly the misread rule 3.6 warns about,
     * because that packet starts with the catalog length (normally 3, for
     * {@code "def"}).</p>
     */
    private void consumeColumnList(byte[] packet, int payloadOffset, int payloadLength, int firstByte) {
        if (firstByte == EOF_PACKET_HEADER || firstByte == OK_PACKET_HEADER) {
            if (session != null) {
                // The number of field definitions is this response's column count.
                session.beginResultSet(columnListCount);
            }
            if (firstByte == EOF_PACKET_HEADER) {
                observeResultSetTerminator(packet, payloadOffset, payloadLength);
            } else {
                applyOkPacket(packet, payloadOffset, payloadLength);
            }
            return;
        }

        columnListCount++;
        responsePhase = MySQLResponsePhase.COLUMN_LIST;
    }

    /**
     * Consumes one packet of a {@code COM_STMT_PREPARE} response.
     *
     * <p>The prepare-ok header announces how many parameter and column definitions
     * follow, and each block is terminated by EOF unless the client deprecated
     * EOF. Modeling the tail is what keeps those definitions from being mistaken
     * for a result set header.</p>
     */
    private void handlePrepareResponse(byte[] packet, int payloadOffset, int payloadLength, int firstByte) {
        switch (preparePhase) {
            case HEADER -> {
                if (firstByte != OK_PACKET_HEADER || payloadLength < PREPARE_HEADER_LENGTH) {
                    suspendObservation("malformed COM_STMT_PREPARE response header");
                    return;
                }
                // status (1), statement_id (4), num_columns (2), num_params (2), reserved (1), ...
                prepareColumnDefinitions = safeLongToInt(
                        readLittleEndian(packet, payloadOffset + 5, 2, payloadOffset + payloadLength));
                prepareParamDefinitions = safeLongToInt(
                        readLittleEndian(packet, payloadOffset + 7, 2, payloadOffset + payloadLength));
                if (session != null) {
                    // The statement now exists server-side until it is closed.
                    session.markPreparedStatementOpened();
                }
                preparePhase = prepareParamDefinitions > 0
                        ? MySQLPreparePhase.PARAM_DEFINITIONS
                        : phaseAfterPrepareParams();
            }
            case PARAM_DEFINITIONS -> {
                prepareParamDefinitions--;
                if (prepareParamDefinitions <= 0) {
                    preparePhase = hasCapability(MySQLCapability.CLIENT_DEPRECATE_EOF)
                            ? phaseAfterPrepareParams()
                            : MySQLPreparePhase.PARAM_TERMINATOR;
                }
            }
            case PARAM_TERMINATOR -> preparePhase = phaseAfterPrepareParams();
            case COLUMN_DEFINITIONS -> {
                prepareColumnDefinitions--;
                if (prepareColumnDefinitions <= 0) {
                    preparePhase = hasCapability(MySQLCapability.CLIENT_DEPRECATE_EOF)
                            ? MySQLPreparePhase.DONE
                            : MySQLPreparePhase.COLUMN_TERMINATOR;
                }
            }
            case COLUMN_TERMINATOR -> preparePhase = MySQLPreparePhase.DONE;
            case DONE -> {
                // Nothing left to consume; the header already completed this response.
            }
        }

        if (preparePhase == MySQLPreparePhase.DONE) {
            completeResponse();
        } else {
            responsePhase = MySQLResponsePhase.PREPARE;
        }
    }

    private MySQLPreparePhase phaseAfterPrepareParams() {
        return prepareColumnDefinitions > 0 ? MySQLPreparePhase.COLUMN_DEFINITIONS : MySQLPreparePhase.DONE;
    }

    /**
     * Applies an OK packet (also the OK-as-EOF terminator) and decides whether a
     * further result set follows.
     */
    private void applyOkPacket(byte[] packet, int payloadOffset, int payloadLength) {
        // An OK response also closes a pending LOCAL INFILE upload.
        finishLocalInfile();

        if (payloadLength < 7) {
            completeResponse();
            return;
        }

        int payloadEnd = payloadOffset + payloadLength;
        int cursor = payloadOffset + 1;
        LengthEncodedInteger affectedRows = readLengthEncodedInteger(packet, cursor, payloadEnd);
        cursor = affectedRows.nextOffset();
        LengthEncodedInteger lastInsertId = readLengthEncodedInteger(packet, cursor, payloadEnd);
        cursor = lastInsertId.nextOffset();
        if (cursor + 4 > payloadEnd) {
            completeResponse();
            return;
        }

        int statusFlags = (packet[cursor] & 0xFF) | ((packet[cursor + 1] & 0xFF) << 8);
        int warningCount = (packet[cursor + 2] & 0xFF) | ((packet[cursor + 3] & 0xFF) << 8);
        if (session != null) {
            session.setLastAffectedRows(affectedRows.value());
            session.setLastWarningCount(warningCount);
            session.applyStatusFlags(statusFlags);
        }
        if (hasMoreResults(statusFlags)) {
            responsePhase = MySQLResponsePhase.RESPONSE_HEADER;
            resultSetInProgress = false;
        } else {
            completeResponse();
        }
    }

    /**
     * Applies the final packet of a result set. Its status flags are the source
     * of truth for the client-visible transaction state, and two layouts exist
     * depending on whether the client negotiated CLIENT_DEPRECATE_EOF.
     */
    private void observeResultSetTerminator(byte[] packet, int payloadOffset, int payloadLength) {
        int statusFlags;
        int warningCount;

        if (hasCapability(MySQLCapability.CLIENT_DEPRECATE_EOF)) {
            // OK-as-EOF reuses the OK packet layout behind a 0xFE header.
            int payloadEnd = payloadOffset + payloadLength;
            int cursor = payloadOffset + 1;
            LengthEncodedInteger affectedRows = readLengthEncodedInteger(packet, cursor, payloadEnd);
            cursor = affectedRows.nextOffset();
            LengthEncodedInteger lastInsertId = readLengthEncodedInteger(packet, cursor, payloadEnd);
            cursor = lastInsertId.nextOffset();
            if (cursor + 4 > payloadEnd) {
                completeResponse();
                return;
            }

            statusFlags = (packet[cursor] & 0xFF) | ((packet[cursor + 1] & 0xFF) << 8);
            warningCount = (packet[cursor + 2] & 0xFF) | ((packet[cursor + 3] & 0xFF) << 8);
            if (session != null) {
                session.setLastAffectedRows(affectedRows.value());
                session.setLastWarningCount(warningCount);
            }
        } else {
            // Classic EOF packet: header, warnings (2), status flags (2).
            if (payloadLength < 5) {
                completeResponse();
                return;
            }
            warningCount = (packet[payloadOffset + 1] & 0xFF) | ((packet[payloadOffset + 2] & 0xFF) << 8);
            statusFlags = (packet[payloadOffset + 3] & 0xFF) | ((packet[payloadOffset + 4] & 0xFF) << 8);
            if (session != null) {
                session.setLastWarningCount(warningCount);
            }
        }

        if (session != null) {
            session.applyStatusFlags(statusFlags);
        }
        if (hasMoreResults(statusFlags)) {
            responsePhase = MySQLResponsePhase.RESPONSE_HEADER;
            resultSetInProgress = false;
        } else {
            completeResponse();
        }
    }

    private boolean hasMoreResults(int statusFlags) {
        return (statusFlags & MySQLServerStatusFlag.SERVER_MORE_RESULTS_EXISTS.getFlag()) != 0;
    }

    private void observeErrPacket(byte[] packet, int payloadOffset, int payloadLength) {
        if (session == null) {
            return;
        }

        int payloadEnd = payloadOffset + payloadLength;
        if (payloadOffset + 3 > payloadEnd) {
            return;
        }

        int cursor = payloadOffset + 3;
        if (cursor + 6 <= payloadEnd && packet[cursor] == '#') {
            session.setLastSqlState(new String(packet, cursor + 1, 5, StandardCharsets.US_ASCII));
        }
    }

    private void compactServer(byte[] buffered, int consumed) {
        pendingServerBytes.reset();
        if (consumed < buffered.length) {
            pendingServerBytes.write(buffered, consumed, buffered.length - consumed);
        }
    }

    private void compact(byte[] buffered, int consumed) {
        pendingBytes.reset();
        if (consumed < buffered.length) {
            pendingBytes.write(buffered, consumed, buffered.length - consumed);
        }
    }

    private void advanceSession(ProtocolConnectionState state) {
        if (session != null) {
            session.tryTransitionTo(state);
        }
    }

    /**
     * Ends the current command response: the observer returns to IDLE and the
     * session moves back to READY, closing the EXECUTING command cycle.
     */
    private void completeResponse() {
        responsePhase = MySQLResponsePhase.IDLE;
        resultSetInProgress = false;
        pendingResponseShape = null;
        advanceSession(ProtocolConnectionState.READY);
    }

    /**
     * Declares the response a command will produce before any response packet is
     * seen (rule 3.6).
     *
     * @return {@code false} when the command draws no observable response, so no
     *         command cycle may start
     */
    private boolean prepareResponseObservation(MySQLCommandType command) {
        MySQLResponseShape shape = command.getResponseShape();
        if (!command.expectsResponse() || shape == MySQLResponseShape.NO_RESPONSE) {
            return false;
        }
        if (shape == MySQLResponseShape.UNKNOWN) {
            suspendObservation("command " + command.name() + " has an unknown response shape");
            return false;
        }
        if (shape == MySQLResponseShape.STREAM) {
            suspendObservation("command " + command.name() + " starts a response stream that never ends");
            return false;
        }

        pendingResponseShape = shape;
        if (shape == MySQLResponseShape.PREPARE) {
            preparePhase = MySQLPreparePhase.HEADER;
            prepareParamDefinitions = 0;
            prepareColumnDefinitions = 0;
        }
        return true;
    }

    /**
     * Observation resync point (rule 2.10).
     *
     * <p>A client command packet is the one boundary the observer can always
     * trust, because every command starts at sequence 0 (rule 3.2). Any
     * misalignment left behind by the previous response is discarded here, and
     * confidence returns to {@code CONFIRMED}.</p>
     */
    private void resyncObservation() {
        responsePhase = MySQLResponsePhase.RESPONSE_HEADER;
        resultSetInProgress = false;
        remainingColumnDefinitions = 0;
        columnListCount = 0;
        pendingResponseShape = null;
        responseObservationSuspended = false;
        pendingServerBytes.reset();
        if (session != null) {
            session.confirmObservation();
        }
    }

    /**
     * Stops interpreting response packets until the next client command.
     *
     * <p>Observation is a best-effort side channel: when a response cannot be
     * interpreted it must stop rather than guess, and it must never affect byte
     * forwarding (rule 2.10).</p>
     */
    private void suspendObservation(String detail) {
        recordProtocolAnomaly(detail);
        responseObservationSuspended = true;
        responsePhase = MySQLResponsePhase.IDLE;
        resultSetInProgress = false;
        remainingColumnDefinitions = 0;
        pendingResponseShape = null;
        pendingServerBytes.reset();
        if (session != null) {
            session.suspendObservation();
        }
    }

    /**
     * Number of frame anomalies observed (unexpected sequence ids, malformed
     * headers). Recorded for diagnostics only: bytes are always forwarded and
     * observation continues.
     */
    public long getProtocolAnomalyCount() {
        return protocolAnomalies.get();
    }

    private void recordProtocolAnomaly(String detail) {
        protocolAnomalies.incrementAndGet();
        if (session != null) {
            // An anomaly degrades observation without stopping it (rule 2.10).
            session.markObservationUncertain();
        }
        if (protocolAnomalyLogged.compareAndSet(false, true)) {
            log.warn("MySQL protocol anomaly on session {}: {} (further anomalies are counted only)",
                    sessionId, detail);
        }
    }

    /**
     * Ends a LOAD DATA LOCAL INFILE upload. Called when the server finally
     * answers with OK or ERR, which also marks the session ready for commands
     * again.
     */
    private void finishLocalInfile() {
        if (!localInfileInProgress) {
            return;
        }
        localInfileInProgress = false;
        advanceSession(ProtocolConnectionState.READY);
    }

    /**
     * Reads client identity and requested database from a MySQL Handshake
     * Response 41, matching rule 3.4. Only identity fields are kept; the auth
     * response is skipped and never stored or logged.
     */
    private void observeHandshakeResponse(byte[] buffered, int payloadLength, long capabilityFlags) {
        if (session == null) {
            return;
        }

        int payloadOffset = MySQLFrameCodec.HEADER_LENGTH;
        int payloadEnd = payloadOffset + payloadLength;
        int cursor = payloadOffset + 32;
        if (cursor > payloadEnd) {
            return;
        }

        CursorResult username = readNullTerminated(buffered, cursor, payloadEnd);
        if (username == null) {
            return;
        }
        session.putAttribute("client.user", username.value());
        cursor = username.nextOffset();

        cursor = skipAuthResponse(buffered, cursor, payloadEnd, capabilityFlags);
        if (cursor < 0) {
            return;
        }

        if ((capabilityFlags & MySQLCapability.CLIENT_CONNECT_WITH_DB.getFlag()) != 0) {
            CursorResult database = readNullTerminated(buffered, cursor, payloadEnd);
            if (database != null && !database.value().isEmpty()) {
                session.setCurrentDatabase(database.value());
                session.putAttribute("client.database", database.value());
            }
        }
    }

    private void observeInitDb(byte[] packet, int offset, int endExclusive) {
        if (session == null || offset >= endExclusive) {
            return;
        }

        String database = new String(packet, offset, endExclusive - offset, StandardCharsets.UTF_8);
        if (!database.isBlank()) {
            session.setCurrentDatabase(database);
        }
    }

    /**
     * Reads the new client identity and default database from
     * {@code COM_CHANGE_USER}. As with the initial handshake response, the
     * authentication payload is skipped and never stored or logged.
     */
    private void observeChangeUser(byte[] packet, int offset, int endExclusive) {
        if (session == null) {
            return;
        }

        CursorResult username = readNullTerminated(packet, offset, endExclusive);
        if (username == null) {
            return;
        }
        session.putAttribute("client.user", username.value());

        int cursor = skipAuthResponse(packet, username.nextOffset(), endExclusive, clientCapabilityFlags);
        if (cursor < 0) {
            return;
        }

        CursorResult database = readNullTerminated(packet, cursor, endExclusive);
        if (database != null && !database.value().isEmpty()) {
            session.setCurrentDatabase(database.value());
            session.putAttribute("client.database", database.value());
        }
    }

    private static CursorResult readNullTerminated(byte[] bytes, int offset, int endExclusive) {
        if (offset >= endExclusive) {
            return null;
        }

        int cursor = offset;
        while (cursor < endExclusive && bytes[cursor] != 0) {
            cursor++;
        }
        if (cursor >= endExclusive) {
            return null;
        }
        return new CursorResult(new String(bytes, offset, cursor - offset, StandardCharsets.UTF_8), cursor + 1);
    }

    private static int skipAuthResponse(byte[] bytes, int offset, int endExclusive, long capabilityFlags) {
        if (offset >= endExclusive) {
            return -1;
        }

        if ((capabilityFlags & MySQLCapability.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA.getFlag()) != 0) {
            LengthEncodedInteger length = readLengthEncodedInteger(bytes, offset, endExclusive);
            int nextOffset = length.nextOffset() + safeLongToInt(length.value());
            return nextOffset <= endExclusive ? nextOffset : -1;
        }
        if ((capabilityFlags & MySQLCapability.CLIENT_SECURE_CONNECTION.getFlag()) != 0) {
            int nextOffset = offset + 1 + (bytes[offset] & 0xFF);
            return nextOffset <= endExclusive ? nextOffset : -1;
        }

        CursorResult terminated = readNullTerminated(bytes, offset, endExclusive);
        return terminated == null ? -1 : terminated.nextOffset();
    }

    private record LengthEncodedInteger(long value, int nextOffset) {
    }

    private record CursorResult(String value, int nextOffset) {
    }

    /**
     * Tracks where the observer is inside a MySQL response so a result set
     * terminator can be told apart from the EOF that follows column definitions.
     */
    private enum MySQLResponsePhase {
        /** No response in flight. */
        IDLE,
        /** Expecting the first packet of a response; the branch comes from the declared shape. */
        RESPONSE_HEADER,
        /** Counting down the column definition packets of a result set. */
        COLUMN_DEFINITIONS,
        /** Skipping the EOF that follows column definitions (without CLIENT_DEPRECATE_EOF). */
        COLUMN_TERMINATOR,
        /** Consuming the column definitions of a COM_FIELD_LIST response. */
        COLUMN_LIST,
        /** Consuming the metadata tail of a COM_STMT_PREPARE response. */
        PREPARE,
        /** Consuming row packets until the result set terminator. */
        ROWS
    }

    /**
     * Position inside a {@code COM_STMT_PREPARE} metadata tail.
     */
    private enum MySQLPreparePhase {
        /** The prepare-ok header announcing the metadata counts. */
        HEADER,
        /** Parameter definition packets. */
        PARAM_DEFINITIONS,
        /** The EOF closing the parameter definitions. */
        PARAM_TERMINATOR,
        /** Column definition packets. */
        COLUMN_DEFINITIONS,
        /** The EOF closing the column definitions. */
        COLUMN_TERMINATOR,
        /** Everything the header announced has been consumed. */
        DONE
    }
}
