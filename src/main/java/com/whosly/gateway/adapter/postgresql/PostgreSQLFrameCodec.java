package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.ProtocolException;
import com.whosly.gateway.adapter.protocol.ProtocolFrameCodec;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * PostgreSQL message codec for typed messages and untyped startup messages.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class PostgreSQLFrameCodec implements ProtocolFrameCodec {

    @Override
    public ProtocolMessage read(InputStream inputStream) throws IOException {
        int type = inputStream.read();
        if (type < 0) {
            throw new EOFException("Unexpected end of PostgreSQL message type");
        }
        int length = readInt4(inputStream);
        validateLength(length);
        byte[] payload = readFully(inputStream, length - 4);
        return ProtocolMessage.typed((char) type, payload);
    }

    public ProtocolMessage readStartupMessage(InputStream inputStream) throws IOException {
        int length = readInt4(inputStream);
        validateLength(length);
        byte[] payload = readFully(inputStream, length - 4);
        return ProtocolMessage.untyped(payload);
    }

    @Override
    public void write(ProtocolMessage message, OutputStream outputStream) throws IOException {
        char type = message.type()
                .orElseThrow(() -> new ProtocolException("PostgreSQL typed message requires type"));
        byte[] payload = message.payload();
        int length = payload.length + 4;
        outputStream.write((byte) type);
        writeInt4(outputStream, length);
        outputStream.write(payload);
    }

    public byte[] packet(char type, byte[] payload) {
        try {
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            write(ProtocolMessage.typed(type, payload), outputStream);
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new ProtocolException("Failed to create PostgreSQL message", e);
        }
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
        if (length < 4) {
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
