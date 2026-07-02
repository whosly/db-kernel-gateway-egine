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
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class MySQLFrameCodec implements ProtocolFrameCodec {

    private static final int HEADER_LENGTH = 4;
    private static final int MAX_SINGLE_PACKET_PAYLOAD = 0xFFFFFF;

    @Override
    public ProtocolMessage read(InputStream inputStream) throws IOException {
        byte[] header = readFully(inputStream, HEADER_LENGTH);
        int payloadLength = (header[0] & 0xFF)
                | ((header[1] & 0xFF) << 8)
                | ((header[2] & 0xFF) << 16);
        int sequenceId = header[3] & 0xFF;
        byte[] payload = readFully(inputStream, payloadLength);
        return ProtocolMessage.untyped(payload, sequenceId);
    }

    public ProtocolMessage readPacket(InputStream inputStream) throws IOException {
        return read(inputStream);
    }

    @Override
    public void write(ProtocolMessage message, OutputStream outputStream) throws IOException {
        byte[] payload = message.payload();
        if (payload.length > MAX_SINGLE_PACKET_PAYLOAD) {
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
