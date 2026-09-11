package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.ProtocolException;
import com.whosly.gateway.adapter.protocol.ProtocolFrameCodec;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * PostgreSQL message codec for typed messages and untyped startup messages.
 *
 * <p>The codec owns every PostgreSQL message header rule. Streaming callers use
 * {@link #read(InputStream)} / {@link #readStartupMessage(InputStream)}, while
 * byte-array observers use {@link #readInt4(byte[], int, int)} and the header
 * length constants so header parsing is never re-implemented by upper layers.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class PostgreSQLFrameCodec implements ProtocolFrameCodec {

    /** Untyped startup-family message: 4-byte big-endian length, no type byte. */
    public static final int UNTYPED_HEADER_LENGTH = 4;

    /** Typed message: 1-byte message type plus 4-byte big-endian length. */
    public static final int TYPED_HEADER_LENGTH = 5;

    /** PostgreSQL requires the length field to be at least 4 bytes. */
    public static final int MIN_MESSAGE_LENGTH = 4;

    /** Frontend {@code CancelRequest} request code. */
    public static final int CANCEL_REQUEST_CODE = 80877102;

    /** Frontend {@code SSLRequest} request code. */
    public static final int SSL_REQUEST_CODE = 80877103;

    /** Frontend {@code GSSENCRequest} request code. */
    public static final int GSSENC_REQUEST_CODE = 80877104;

    @Override
    public ProtocolMessage read(InputStream inputStream) throws IOException {
        int type = inputStream.read();
        if (type < 0) {
            throw new EOFException("Unexpected end of PostgreSQL message type");
        }
        int length = readInt4(inputStream);
        validateLength(length);
        byte[] payload = readFully(inputStream, length - UNTYPED_HEADER_LENGTH);
        return ProtocolMessage.typed((char) type, payload);
    }

    public ProtocolMessage readStartupMessage(InputStream inputStream) throws IOException {
        int length = readInt4(inputStream);
        validateLength(length);
        byte[] payload = readFully(inputStream, length - UNTYPED_HEADER_LENGTH);
        return ProtocolMessage.untyped(payload);
    }

    @Override
    public void write(ProtocolMessage message, OutputStream outputStream) throws IOException {
        char type = message.type()
                .orElseThrow(() -> new ProtocolException("PostgreSQL typed message requires type"));
        byte[] payload = message.payload();
        int length = payload.length + UNTYPED_HEADER_LENGTH;
        outputStream.write((byte) type);
        writeInt4(outputStream, length);
        outputStream.write(payload);
    }

    public byte[] packet(char type, byte[] payload) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            write(ProtocolMessage.typed(type, payload), outputStream);
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new ProtocolException("Failed to create PostgreSQL message", e);
        }
    }

    /**
     * Reads a big-endian 4-byte integer from a byte array.
     *
     * @return the value, or {@code -1} when four bytes are not available
     */
    public static int readInt4(byte[] bytes, int offset, int endExclusive) {
        if (bytes == null || offset < 0 || offset + 4 > endExclusive) {
            return -1;
        }
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private static int readInt4(InputStream inputStream) throws IOException {
        byte[] bytes = readFully(inputStream, 4);
        return ((bytes[0] & 0xFF) << 24)
                | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8)
                | (bytes[3] & 0xFF);
    }

    private static void writeInt4(OutputStream outputStream, int value) throws IOException {
        outputStream.write((value >> 24) & 0xFF);
        outputStream.write((value >> 16) & 0xFF);
        outputStream.write((value >> 8) & 0xFF);
        outputStream.write(value & 0xFF);
    }

    private static void validateLength(int length) {
        if (length < MIN_MESSAGE_LENGTH) {
            throw new ProtocolException("PostgreSQL message length must be at least 4: " + length);
        }
    }

    private static byte[] readFully(InputStream inputStream, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = inputStream.read(bytes, offset, length - offset);
            if (count < 0) {
                throw new EOFException("Unexpected end of PostgreSQL message");
            }
            offset += count;
        }
        return bytes;
    }
}
