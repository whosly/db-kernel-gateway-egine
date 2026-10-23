package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.ProtocolException;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * One complete TDS logical message (one or more physical packets ending in EOM).
 *
 * <p>Preserves raw wire bytes for transparent replay — callers must not re-encode
 * headers when forwarding PreLogin / Login7 during deferred identity routing.</p>
 */
public final class TdsLogicalMessage {

    private static final int MAX_PACKETS = 64;

    private final int type;
    private final byte[] wireBytes;
    private final byte[] payload;

    private TdsLogicalMessage(int type, byte[] wireBytes, byte[] payload) {
        this.type = type & 0xFF;
        this.wireBytes = Objects.requireNonNull(wireBytes, "wireBytes");
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    public int type() {
        return type;
    }

    /** Exact on-wire bytes (all physical packets). */
    public byte[] wireBytes() {
        return Arrays.copyOf(wireBytes, wireBytes.length);
    }

    /** Concatenated packet payloads (no headers). */
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public void writeTo(OutputStream out) throws IOException {
        out.write(wireBytes);
        out.flush();
    }

    /**
     * Reads packets until EOM. Caps physical packet count to bound hostile streams.
     */
    public static TdsLogicalMessage read(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int type = -1;
        for (int i = 0; i < MAX_PACKETS; i++) {
            byte[] header = readFully(in, TdsFrameCodec.HEADER_LENGTH);
            int packetLength = TdsFrameCodec.packetLength(header, 0, header.length);
            if (packetLength < TdsFrameCodec.MIN_PACKET_LENGTH) {
                throw new ProtocolException("TDS packet length too small: " + packetLength);
            }
            if (packetLength > TdsFrameCodec.MAX_PACKET_LENGTH) {
                throw new ProtocolException("TDS packet length too large: " + packetLength);
            }
            int status = TdsFrameCodec.status(header, 0, header.length);
            type = TdsFrameCodec.type(header, 0, header.length);
            int payloadLen = packetLength - TdsFrameCodec.HEADER_LENGTH;
            byte[] packetPayload = payloadLen == 0 ? new byte[0] : readFully(in, payloadLen);
            wire.write(header);
            wire.write(packetPayload);
            body.write(packetPayload);
            if (TdsPacketStatus.isEom(status)) {
                return new TdsLogicalMessage(type, wire.toByteArray(), body.toByteArray());
            }
        }
        throw new ProtocolException("TDS logical message exceeded " + MAX_PACKETS + " physical packets");
    }

    /** Builds a single-packet (EOM) logical message for tests and helpers. */
    public static TdsLogicalMessage singlePacket(int type, byte[] payload, int packetId) {
        TdsFrameCodec codec = new TdsFrameCodec();
        byte[] frame = codec.packet(type, payload == null ? new byte[0] : payload, packetId);
        byte[] body = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        return new TdsLogicalMessage(type, frame, body);
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
}
