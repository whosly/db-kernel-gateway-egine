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
 */
public class MySQLSqlEventExtractor {

    private static final int HEADER_LENGTH = 4;

    private final String protocolName;
    private final String sessionId;
    private final boolean commandPhaseOnly;
    private final ByteArrayOutputStream pendingBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream pendingTargetBytes = new ByteArrayOutputStream();
    private boolean readyForCommands;

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
        if (direction == TrafficDirection.TARGET_TO_CLIENT) {
            observeTargetBytes(bytes, offset, length);
            return List.of();
        }

        if (commandPhaseOnly && !readyForCommands) {
            return List.of();
        }

        return extractClientCommandBytes(bytes, offset, length);
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

        String sql = new String(packet, payloadOffset + 1, payloadLength - 1, StandardCharsets.UTF_8);
        if (sql.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(SqlTrafficEvent.builder(protocolName, sessionId, command.name(), sql).build());
    }

    private void compact(byte[] buffered, int consumed) {
        pendingBytes.reset();
        if (consumed < buffered.length) {
            pendingBytes.write(buffered, consumed, buffered.length - consumed);
        }
    }
}
