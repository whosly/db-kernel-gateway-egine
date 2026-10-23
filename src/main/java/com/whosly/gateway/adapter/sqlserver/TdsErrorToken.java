package com.whosly.gateway.adapter.sqlserver;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * MS-TDS ERROR token ({@code 0xAA}) parse/build for observation and gateway forge.
 *
 * <p>Layout (after packet payload concat): {@code TokenType | Length(USHORT) |
 * Number(LONG) | State | Class | MsgText(US_VARCHAR) | ServerName(B_VARCHAR) |
 * ProcName(B_VARCHAR) | LineNumber(USHORT|LONG)}.</p>
 *
 * <p>Unicode lengths are character counts; characters are UCS-2LE. LineNumber is
 * LONG for TDS 7.2+; the parser accepts either remaining width. Password-like
 * fields never appear here.</p>
 */
public final class TdsErrorToken {

    /** Token type byte. */
    public static final int TOKEN_TYPE = 0xAA;

    private final int number;
    private final int state;
    private final int severityClass;
    private final String message;
    private final String serverName;
    private final String procName;
    private final long lineNumber;
    /** Bytes consumed from the token type through LineNumber inclusive. */
    private final int wireLength;

    private TdsErrorToken(
            int number,
            int state,
            int severityClass,
            String message,
            String serverName,
            String procName,
            long lineNumber,
            int wireLength) {
        this.number = number;
        this.state = state;
        this.severityClass = severityClass;
        this.message = message;
        this.serverName = serverName;
        this.procName = procName;
        this.lineNumber = lineNumber;
        this.wireLength = wireLength;
    }

    public int number() {
        return number;
    }

    public int state() {
        return state;
    }

    public int severityClass() {
        return severityClass;
    }

    public String message() {
        return message;
    }

    public String serverName() {
        return serverName;
    }

    public String procName() {
        return procName;
    }

    public long lineNumber() {
        return lineNumber;
    }

    public int wireLength() {
        return wireLength;
    }

    /**
     * Best-effort parse starting at {@code offset} where {@code bytes[offset] == 0xAA}.
     *
     * @return empty when truncated or length inconsistent
     */
    public static Optional<TdsErrorToken> tryParse(byte[] bytes, int offset) {
        if (bytes == null || offset < 0 || offset + 3 > bytes.length) {
            return Optional.empty();
        }
        if ((bytes[offset] & 0xFF) != TOKEN_TYPE) {
            return Optional.empty();
        }
        int length = u16le(bytes, offset + 1);
        int bodyStart = offset + 3;
        int bodyEnd = bodyStart + length;
        if (length < 6 || bodyEnd > bytes.length) {
            return Optional.empty();
        }
        int cursor = bodyStart;
        int number = i32le(bytes, cursor);
        cursor += 4;
        int state = bytes[cursor++] & 0xFF;
        int severity = bytes[cursor++] & 0xFF;
        Optional<String> msg = readUsVarchar(bytes, cursor, bodyEnd);
        if (msg.isEmpty()) {
            return Optional.empty();
        }
        cursor += 2 + (msg.get().length() * 2);
        Optional<String> server = readBVarchar(bytes, cursor, bodyEnd);
        if (server.isEmpty()) {
            return Optional.empty();
        }
        cursor += 1 + (server.get().length() * 2);
        Optional<String> proc = readBVarchar(bytes, cursor, bodyEnd);
        if (proc.isEmpty()) {
            return Optional.empty();
        }
        cursor += 1 + (proc.get().length() * 2);
        long line;
        int remaining = bodyEnd - cursor;
        if (remaining >= 4) {
            line = u32le(bytes, cursor) & 0xFFFF_FFFFL;
            cursor += 4;
        } else if (remaining >= 2) {
            line = u16le(bytes, cursor);
            cursor += 2;
        } else if (remaining == 0) {
            line = 0;
        } else {
            return Optional.empty();
        }
        // Length field must cover Number..LineNumber; tolerate trailing padding inside Length.
        if (cursor > bodyEnd) {
            return Optional.empty();
        }
        return Optional.of(new TdsErrorToken(
                number, state, severity, msg.get(), server.get(), proc.get(), line, bodyEnd - offset));
    }

    /**
     * Encodes one ERROR token (TDS 7.2+ LONG LineNumber) with empty ProcName.
     */
    public static byte[] encode(int number, int state, int severityClass, String message, String serverName) {
        String msg = message == null ? "" : message;
        String server = serverName == null ? "" : serverName;
        byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_16LE);
        byte[] serverBytes = server.getBytes(StandardCharsets.UTF_16LE);
        if ((msgBytes.length & 1) != 0 || (serverBytes.length & 1) != 0) {
            throw new IllegalArgumentException("UCS-2LE payload must be even length");
        }
        int msgChars = msgBytes.length / 2;
        int serverChars = serverBytes.length / 2;
        // Number(4)+State(1)+Class(1)+US_VARCHAR + B_VARCHAR + B_VARCHAR(empty=1) + Line(4)
        int bodyLen = 4 + 1 + 1 + (2 + msgBytes.length) + (1 + serverBytes.length) + 1 + 4;
        ByteBuffer buf = ByteBuffer.allocate(3 + bodyLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) TOKEN_TYPE);
        buf.putShort((short) bodyLen);
        buf.putInt(number);
        buf.put((byte) (state & 0xFF));
        buf.put((byte) (severityClass & 0xFF));
        buf.putShort((short) msgChars);
        buf.put(msgBytes);
        buf.put((byte) (serverChars & 0xFF));
        buf.put(serverBytes);
        buf.put((byte) 0); // empty ProcName
        buf.putInt(0); // LineNumber
        return buf.array();
    }

    private static Optional<String> readUsVarchar(byte[] bytes, int offset, int endExclusive) {
        if (offset + 2 > endExclusive) {
            return Optional.empty();
        }
        int chars = u16le(bytes, offset);
        int byteLen = chars * 2;
        if (offset + 2 + byteLen > endExclusive) {
            return Optional.empty();
        }
        return Optional.of(new String(bytes, offset + 2, byteLen, StandardCharsets.UTF_16LE));
    }

    private static Optional<String> readBVarchar(byte[] bytes, int offset, int endExclusive) {
        if (offset + 1 > endExclusive) {
            return Optional.empty();
        }
        int chars = bytes[offset] & 0xFF;
        int byteLen = chars * 2;
        if (offset + 1 + byteLen > endExclusive) {
            return Optional.empty();
        }
        return Optional.of(new String(bytes, offset + 1, byteLen, StandardCharsets.UTF_16LE));
    }

    private static int u16le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static int i32le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static int u32le(byte[] bytes, int offset) {
        return i32le(bytes, offset);
    }
}
