package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.postgresql.PostgreSQLMessageFraming.Framing;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PostgreSQLMessageFramingTest {

    @Test
    void reportsOneBoundaryPerTypedMessage() {
        byte[] first = typed('Q', "select 1");
        byte[] second = typed('Z', "I");
        byte[] window = concat(first, second);

        assertThat(PostgreSQLMessageFraming.completeMessageEnds(window, 0, window.length, Framing.TYPED))
                .containsExactly(first.length, window.length);
    }

    @Test
    void waitsForTheRestOfATypedMessage() {
        byte[] message = typed('Q', "select 1");

        assertThat(PostgreSQLMessageFraming.completeMessageEnds(message, 0, 4, Framing.TYPED)).isEmpty();
        assertThat(PostgreSQLMessageFraming.completeMessageEnds(message, 0, message.length, Framing.TYPED))
                .containsExactly(message.length);
    }

    @Test
    void treatsOnlyTheLeadingMessageOfTheStartupFamilyAsUntyped() {
        byte[] startup = untyped(8, new byte[]{0x00, 0x03, 0x00, 0x00});
        byte[] query = typed('Q', "select 1");
        byte[] window = concat(startup, query);

        assertThat(PostgreSQLMessageFraming.completeMessageEnds(window, 0, window.length, Framing.STARTUP_FAMILY))
                .containsExactly(startup.length, window.length);
    }

    @Test
    void waitsForTheRestOfAnUntypedStartupMessage() {
        byte[] startup = untyped(8, new byte[]{0x00, 0x03, 0x00, 0x00});

        assertThat(PostgreSQLMessageFraming.completeMessageEnds(startup, 0, 6, Framing.STARTUP_FAMILY)).isEmpty();
    }

    @Test
    void framesTheEncryptionResponseAsASingleByte() {
        assertThat(PostgreSQLMessageFraming.completeMessageEnds(new byte[]{'S'}, 0, 1, Framing.ENCRYPTION_RESPONSE))
                .containsExactly(1);
        assertThat(PostgreSQLMessageFraming.completeMessageEnds(new byte[0], 0, 0, Framing.ENCRYPTION_RESPONSE))
                .isEmpty();
    }

    @Test
    void refusesToGuessWhenALengthCannotBeValid() {
        byte[] tooShort = new byte[]{'Q', 0x00, 0x00, 0x00, 0x02, 'x'};
        byte[] untypedTooShort = new byte[]{0x00, 0x00, 0x00, 0x02, 'x'};

        assertThat(PostgreSQLMessageFraming.completeMessageEnds(tooShort, 0, tooShort.length, Framing.TYPED))
                .isNull();
        assertThat(PostgreSQLMessageFraming.completeMessageEnds(
                untypedTooShort, 0, untypedTooShort.length, Framing.STARTUP_FAMILY)).isNull();
    }

    @Test
    void reportsOffsetsRelativeToTheWindowStart() {
        byte[] first = typed('Q', "select 1");
        byte[] second = typed('Z', "I");
        byte[] window = concat(first, second);

        assertThat(PostgreSQLMessageFraming.completeMessageEnds(window, first.length, second.length, Framing.TYPED))
                .containsExactly(second.length);
    }

    private static byte[] typed(char type, String payload) {
        byte[] body = payload.getBytes(StandardCharsets.US_ASCII);
        byte[] message = new byte[1 + 4 + body.length];
        message[0] = (byte) type;
        writeLength(message, 1, 4 + body.length);
        System.arraycopy(body, 0, message, 5, body.length);
        return message;
    }

    private static byte[] untyped(int length, byte[] payload) {
        byte[] message = new byte[length];
        writeLength(message, 0, length);
        System.arraycopy(payload, 0, message, 4, payload.length);
        return message;
    }

    private static void writeLength(byte[] target, int offset, int length) {
        target[offset] = (byte) ((length >> 24) & 0xFF);
        target[offset + 1] = (byte) ((length >> 16) & 0xFF);
        target[offset + 2] = (byte) ((length >> 8) & 0xFF);
        target[offset + 3] = (byte) (length & 0xFF);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.writeBytes(first);
        outputStream.writeBytes(second);
        return outputStream.toByteArray();
    }
}
