package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.LoopbackSockets;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL wire frames and socket scaffolding for tests that need to speak the
 * protocol at the byte level.
 *
 * <p>Kept next to the protocol classes it builds frames for, so a header rule can
 * never be written twice differently: the codec owns lengths and sequence ids, the
 * row helpers own value encoding, and this class only assembles them.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class MySQLTestFrames {

    /** Collation id used for text columns ({@code utf8_general_ci}). */
    public static final int UTF8_COLLATION = 255;

    private static final MySQLFrameCodec CODEC = new MySQLFrameCodec();
    private static final int HEADER_LENGTH = MySQLFrameCodec.HEADER_LENGTH;

    private MySQLTestFrames() {
    }

    /** A client command packet carrying only the command byte. */
    public static byte[] commandPacket(MySQLCommandType command) {
        return CODEC.packet(new byte[]{(byte) command.getCode()}, 0);
    }

    /** A column definition for a nullable text column. */
    public static byte[] columnPayload(String name, int type, int collation) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeLengthEncodedString(payload, "def");
        writeLengthEncodedString(payload, "shop");
        writeLengthEncodedString(payload, "accounts");
        writeLengthEncodedString(payload, "accounts");
        writeLengthEncodedString(payload, name);
        writeLengthEncodedString(payload, name);
        payload.write(0x0C);
        payload.write(collation & 0xFF);
        payload.write((collation >> 8) & 0xFF);
        payload.writeBytes(new byte[]{0x00, 0x00, 0x00, 0x00});
        payload.write(type);
        // Flags: nullable, so rules that refuse NOT NULL columns still apply.
        payload.writeBytes(new byte[]{0x00, 0x00});
        payload.write(0x00);
        payload.writeBytes(new byte[]{0x00, 0x00});
        return payload.toByteArray();
    }

    private static void writeLengthEncodedString(ByteArrayOutputStream payload, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        payload.write(bytes.length);
        payload.writeBytes(bytes);
    }

    /** A text-protocol row payload, where a null value means SQL NULL. */
    public static byte[] rowPayload(String... values) {
        List<byte[]> encoded = new ArrayList<>(values.length);
        for (String value : values) {
            encoded.add(value == null ? null : value.getBytes(StandardCharsets.US_ASCII));
        }
        return MySQLTextRow.encode(encoded);
    }

    /** The terminator that ends the column definitions of a result set. */
    public static byte[] columnDefinitionsTerminator(int sequenceId) {
        return CODEC.packet(new byte[]{(byte) 0xFE, 0x00, 0x00, 0x02, 0x00}, sequenceId);
    }

    public static byte[] packet(byte[] payload, int sequenceId) {
        return CODEC.packet(payload, sequenceId);
    }

    /** Reads one whole packet, header included. */
    public static byte[] readPacket(InputStream inputStream) throws Exception {
        byte[] header = readExact(inputStream, HEADER_LENGTH);
        int payloadLength = MySQLFrameCodec.payloadLength(header, 0, HEADER_LENGTH);
        byte[] payload = readExact(inputStream, payloadLength);
        byte[] packet = new byte[HEADER_LENGTH + payloadLength];
        System.arraycopy(header, 0, packet, 0, HEADER_LENGTH);
        System.arraycopy(payload, 0, packet, HEADER_LENGTH, payloadLength);
        return packet;
    }

    public static byte[] readExact(InputStream inputStream, int length) throws Exception {
        // One implementation of "read exactly this many bytes" for the whole test tree.
        return LoopbackSockets.readExact(inputStream, length);
    }
}
