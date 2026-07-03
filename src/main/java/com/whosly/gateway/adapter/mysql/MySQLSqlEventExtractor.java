package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.SqlTrafficEvent;
import com.whosly.gateway.adapter.protocol.TrafficDirection;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extracts SQL statements from cleartext MySQL command phase packets.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class MySQLSqlEventExtractor {

    private static final int HEADER_LENGTH = 4;
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

    private final String protocolName;
    private final String sessionId;
    private final boolean commandPhaseOnly;
    private final ByteArrayOutputStream pendingBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream pendingTargetBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream pendingHandshakeBytes = new ByteArrayOutputStream();
    private boolean readyForCommands;
    private boolean opaqueTunnel;
    private long clientCapabilityFlags;
    private boolean clientHandshakeResponseSeen;
    private int[] lastQueryAttributeTypes = new int[0];

    public MySQLSqlEventExtractor(String protocolName, String sessionId) {
        this(protocolName, sessionId, true);
    }

    public MySQLSqlEventExtractor(String protocolName, String sessionId, boolean readyForCommands) {
        this.protocolName = protocolName;
        this.sessionId = sessionId;
        this.commandPhaseOnly = !readyForCommands;
        this.readyForCommands = readyForCommands;
    }

    public List<SqlTrafficEvent> extract(byte[] bytes, int offset, int length) {
        return extractClientCommandBytes(bytes, offset, length);
    }

    public List<SqlTrafficEvent> inspect(TrafficDirection direction, byte[] bytes, int offset, int length) {
        if (opaqueTunnel) {
            return List.of();
        }

        if (direction == TrafficDirection.TARGET_TO_CLIENT) {
            observeTargetBytes(bytes, offset, length);
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
        if (buffered.length < HEADER_LENGTH + 4) {
            return;
        }

        int payloadLength = (buffered[0] & 0xFF)
                | ((buffered[1] & 0xFF) << 8)
                | ((buffered[2] & 0xFF) << 16);
        if (payloadLength < 4 || buffered.length < HEADER_LENGTH + payloadLength) {
            return;
        }

        long capabilityFlags = (buffered[HEADER_LENGTH] & 0xFFL)
                | ((buffered[HEADER_LENGTH + 1] & 0xFFL) << 8)
                | ((buffered[HEADER_LENGTH + 2] & 0xFFL) << 16)
                | ((buffered[HEADER_LENGTH + 3] & 0xFFL) << 24);
        clientHandshakeResponseSeen = true;
        pendingHandshakeBytes.reset();
        clientCapabilityFlags |= capabilityFlags;
        if ((capabilityFlags & MySQLCapability.CLIENT_SSL.getFlag()) != 0
                || (capabilityFlags & MySQLCapability.CLIENT_COMPRESS.getFlag()) != 0
                || (capabilityFlags & MySQLCapability.CLIENT_ZSTD_COMPRESSION_ALGORITHM.getFlag()) != 0) {
            opaqueTunnel = true;
            pendingBytes.reset();
            pendingTargetBytes.reset();
            pendingHandshakeBytes.reset();
        }
    }

    private List<SqlTrafficEvent> extractClientCommandBytes(byte[] bytes, int offset, int length) {
        pendingBytes.write(bytes, offset, length);
        byte[] buffered = pendingBytes.toByteArray();
        int cursor = 0;
        List<SqlTrafficEvent> events = new ArrayList<>();

        while (buffered.length - cursor >= HEADER_LENGTH) {
            int payloadLength = (buffered[cursor] & 0xFF)
                    | ((buffered[cursor + 1] & 0xFF) << 8)
                    | ((buffered[cursor + 2] & 0xFF) << 16);
            int packetLength = HEADER_LENGTH + payloadLength;
            if (buffered.length - cursor < packetLength) {
                break;
            }

            Optional<SqlTrafficEvent> event = extractPacket(buffered, cursor + HEADER_LENGTH, payloadLength);
            event.ifPresent(events::add);
            cursor += packetLength;
        }

        compact(buffered, cursor);
        return events;
    }

    private void observeTargetBytes(byte[] bytes, int offset, int length) {
        if (!commandPhaseOnly || readyForCommands) {
            return;
        }

        pendingTargetBytes.write(bytes, offset, length);
        byte[] buffered = pendingTargetBytes.toByteArray();
        int cursor = 0;

        while (buffered.length - cursor >= HEADER_LENGTH + 1) {
            int payloadLength = (buffered[cursor] & 0xFF)
                    | ((buffered[cursor + 1] & 0xFF) << 8)
                    | ((buffered[cursor + 2] & 0xFF) << 16);
            int packetLength = HEADER_LENGTH + payloadLength;
            if (payloadLength <= 0 || buffered.length - cursor < packetLength) {
                break;
            }

            int firstPayloadByte = buffered[cursor + HEADER_LENGTH] & 0xFF;
            if (firstPayloadByte == 0x00) {
                readyForCommands = true;
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

    private Optional<SqlTrafficEvent> extractPacket(byte[] packet, int payloadOffset, int payloadLength) {
        if (payloadLength < 1) {
            return Optional.empty();
        }

        int commandCode = packet[payloadOffset] & 0xFF;
        Optional<MySQLCommandType> commandType = MySQLCommandType.fromCode(commandCode);
        if (commandType.isEmpty()) {
            return Optional.empty();
        }

        MySQLCommandType command = commandType.get();
        if (command != MySQLCommandType.COM_QUERY && command != MySQLCommandType.COM_STMT_PREPARE) {
            return Optional.empty();
        }

        int sqlOffset = payloadOffset + 1;
        if (command == MySQLCommandType.COM_QUERY && hasCapability(MySQLCapability.CLIENT_QUERY_ATTRIBUTES)) {
            sqlOffset = skipQueryAttributes(packet, sqlOffset, payloadOffset + payloadLength);
        }
        if (sqlOffset > payloadOffset + payloadLength) {
            return Optional.empty();
        }

        String sql = new String(packet, sqlOffset, payloadOffset + payloadLength - sqlOffset, StandardCharsets.UTF_8);
        if (sql.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(SqlTrafficEvent.builder(protocolName, sessionId, command.name(), sql).build());
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

    private void compact(byte[] buffered, int consumed) {
        pendingBytes.reset();
        if (consumed < buffered.length) {
            pendingBytes.write(buffered, consumed, buffered.length - consumed);
        }
    }

    private record LengthEncodedInteger(long value, int nextOffset) {
    }
}
