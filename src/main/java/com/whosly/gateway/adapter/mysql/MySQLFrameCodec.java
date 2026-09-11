package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.ProtocolException;
import com.whosly.gateway.adapter.protocol.ProtocolFrameCodec;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * MySQL packet codec: 3-byte little-endian payload length plus sequence id.
 *
 * <p>The codec owns every MySQL packet header rule. Streaming callers use
 * {@link #read(InputStream)} / {@link #write(ProtocolMessage, OutputStream)},
 * while byte-array observers use {@link #payloadLength(byte[], int, int)} and
 * {@link #sequenceId(byte[], int, int)} so header parsing is never re-implemented
 * by upper layers.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class MySQLFrameCodec implements ProtocolFrameCodec {

    /** MySQL packet header length: 3-byte payload length plus 1-byte sequence id. */
    public static final int HEADER_LENGTH = 4;

    /** Maximum payload a single MySQL packet can carry ({@code 2^24 - 1}). */
    public static final int MAX_PAYLOAD_LENGTH = 0xFFFFFF;

    @Override
    public ProtocolMessage read(InputStream inputStream) throws IOException {
        byte[] header = readFully(inputStream, HEADER_LENGTH);
        int payloadLength = payloadLength(header, 0, header.length);
        int sequenceId = sequenceId(header, 0, header.length);
        byte[] payload = readFully(inputStream, payloadLength);
        return ProtocolMessage.untyped(payload, sequenceId);
    }

    public ProtocolMessage readPacket(InputStream inputStream) throws IOException {
        return read(inputStream);
    }

    @Override
    public void write(ProtocolMessage message, OutputStream outputStream) throws IOException {
        byte[] payload = message.payload();
        if (payload.length > MAX_PAYLOAD_LENGTH) {
            throw new ProtocolException("MySQL packet payload too large for single packet: " + payload.length);
        }
        int sequenceId = message.sequence().orElse(0) & 0xFF;
        outputStream.write(payload.length & 0xFF);
        outputStream.write((payload.length >> 8) & 0xFF);
        outputStream.write((payload.length >> 16) & 0xFF);
        outputStream.write(sequenceId);
        outputStream.write(payload);
    }

    public byte[] packet(byte[] payload, int sequenceId) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            write(ProtocolMessage.untyped(payload, sequenceId), outputStream);
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new ProtocolException("Failed to create MySQL packet", e);
        }
    }

    /**
     * Reads the little-endian payload length from a MySQL packet header.
     *
     * @param bytes       buffer holding at least one packet header
     * @param offset      header start offset
     * @param endExclusive buffer end offset
     * @return payload length, or {@code -1} when a full header is not available
     */
    public static int payloadLength(byte[] bytes, int offset, int endExclusive) {
        if (bytes == null || offset < 0 || offset + HEADER_LENGTH > endExclusive) {
            return -1;
        }
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16);
    }

    /**
     * Reads the sequence id byte from a MySQL packet header.
     *
     * @return sequence id, or {@code -1} when a full header is not available
     */
    public static int sequenceId(byte[] bytes, int offset, int endExclusive) {
        if (bytes == null || offset < 0 || offset + HEADER_LENGTH > endExclusive) {
            return -1;
        }
        return bytes[offset + 3] & 0xFF;
    }

    private static byte[] readFully(InputStream inputStream, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = inputStream.read(bytes, offset, length - offset);
            if (count < 0) {
                throw new EOFException("Unexpected end of MySQL packet");
            }
            offset += count;
        }
        return bytes;
    }

}
