package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.ProtocolException;
import com.whosly.gateway.adapter.protocol.ProtocolFrameCodec;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * TDS packet codec: 8-byte header with big-endian total length.
 *
 * <pre>
 *   type(1) | status(1) | length(2 BE, includes header) | spid(2) | packetId(1) | window(1)
 * </pre>
 *
 * <p>The codec owns every TDS packet header rule. Streaming callers use
 * {@link #read(InputStream)} / {@link #write(ProtocolMessage, OutputStream)};
 * observers use {@link #packetLength(byte[], int, int)} so framing math is not
 * re-implemented by upper layers.</p>
 */
public class TdsFrameCodec implements ProtocolFrameCodec {

    /** TDS packet header length. */
    public static final int HEADER_LENGTH = 8;

    /** Minimum legal packet length (header only). */
    public static final int MIN_PACKET_LENGTH = HEADER_LENGTH;

    /** Practical upper bound for a single TDS packet (header length field is uint16). */
    public static final int MAX_PACKET_LENGTH = 0xFFFF;

    @Override
    public ProtocolMessage read(InputStream inputStream) throws IOException {
        byte[] header = readFully(inputStream, HEADER_LENGTH);
        int packetLength = packetLength(header, 0, header.length);
        if (packetLength < MIN_PACKET_LENGTH) {
            throw new ProtocolException("TDS packet length too small: " + packetLength);
        }
        int payloadLength = packetLength - HEADER_LENGTH;
        byte[] payload = payloadLength == 0 ? new byte[0] : readFully(inputStream, payloadLength);
        int type = type(header, 0, header.length);
        int packetId = packetId(header, 0, header.length);
        // Pack type into ProtocolMessage.type as a char (low byte); sequence = packetId.
        return ProtocolMessage.typed((char) type, payload, packetId);
    }

    @Override
    public void write(ProtocolMessage message, OutputStream outputStream) throws IOException {
        byte[] payload = message.payload();
        int packetLength = HEADER_LENGTH + payload.length;
        if (packetLength > MAX_PACKET_LENGTH) {
            throw new ProtocolException("TDS packet too large for single packet: " + packetLength);
        }
        int type = message.type().map(c -> c & 0xFF).orElse(TdsPacketType.TABULAR_RESULT.getCode());
        int packetId = message.sequence().orElse(1) & 0xFF;
        outputStream.write(type);
        outputStream.write(TdsPacketStatus.EOM);
        outputStream.write((packetLength >> 8) & 0xFF);
        outputStream.write(packetLength & 0xFF);
        outputStream.write(0); // SPID hi
        outputStream.write(0); // SPID lo
        outputStream.write(packetId);
        outputStream.write(0); // window
        outputStream.write(payload);
    }

    /**
     * Builds a single-packet TDS frame (EOM set) for tests and helpers.
     */
    public byte[] packet(int type, byte[] payload, int packetId) {
        int packetLength = HEADER_LENGTH + (payload == null ? 0 : payload.length);
        if (packetLength > MAX_PACKET_LENGTH) {
            throw new ProtocolException("TDS packet too large: " + packetLength);
        }
        byte[] frame = new byte[packetLength];
        frame[0] = (byte) (type & 0xFF);
        frame[1] = (byte) TdsPacketStatus.EOM;
        frame[2] = (byte) ((packetLength >> 8) & 0xFF);
        frame[3] = (byte) (packetLength & 0xFF);
        frame[4] = 0;
        frame[5] = 0;
        frame[6] = (byte) (packetId & 0xFF);
        frame[7] = 0;
        if (payload != null && payload.length > 0) {
            System.arraycopy(payload, 0, frame, HEADER_LENGTH, payload.length);
        }
        return frame;
    }

    /**
     * @return total packet length including header, or {@code -1} if the header is incomplete
     */
    public static int packetLength(byte[] bytes, int offset, int endExclusive) {
        if (bytes == null || offset < 0 || endExclusive - offset < HEADER_LENGTH) {
            return -1;
        }
        return ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    public static int type(byte[] bytes, int offset, int endExclusive) {
        if (bytes == null || offset < 0 || endExclusive - offset < 1) {
            return -1;
        }
        return bytes[offset] & 0xFF;
    }

    public static int status(byte[] bytes, int offset, int endExclusive) {
        if (bytes == null || offset < 0 || endExclusive - offset < 2) {
            return -1;
        }
        return bytes[offset + 1] & 0xFF;
    }

    public static int packetId(byte[] bytes, int offset, int endExclusive) {
        if (bytes == null || offset < 0 || endExclusive - offset < 7) {
            return -1;
        }
        return bytes[offset + 6] & 0xFF;
    }

    private static byte[] readFully(InputStream inputStream, int length) throws IOException {
        byte[] buffer = new byte[length];
        int read = 0;
        while (read < length) {
            int n = inputStream.read(buffer, read, length - read);
            if (n < 0) {
                throw new EOFException("Unexpected EOF reading TDS packet (" + read + "/" + length + ")");
            }
            read += n;
        }
        return buffer;
    }

    /** Copies payload bytes out of a complete packet frame. */
    public static byte[] payloadOf(byte[] packet) {
        if (packet == null || packet.length < HEADER_LENGTH) {
            return new byte[0];
        }
        return Arrays.copyOfRange(packet, HEADER_LENGTH, packet.length);
    }
}
