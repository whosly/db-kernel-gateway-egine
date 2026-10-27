package com.whosly.gateway.adapter.sqlserver;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TdsErrorTokenTest {

    @Test
    void encodeRoundTripsThroughTryParse() {
        byte[] wire = TdsErrorToken.encode(18456, 1, 14, "login failed", "sql-lab");
        Optional<TdsErrorToken> parsed = TdsErrorToken.tryParse(wire, 0);

        assertThat(parsed).isPresent();
        TdsErrorToken error = parsed.get();
        assertThat(error.number()).isEqualTo(18456);
        assertThat(error.state()).isEqualTo(1);
        assertThat(error.severityClass()).isEqualTo(14);
        assertThat(error.message()).isEqualTo("login failed");
        assertThat(error.serverName()).isEqualTo("sql-lab");
        assertThat(error.procName()).isEmpty();
        assertThat(error.lineNumber()).isZero();
        assertThat(error.wireLength()).isEqualTo(wire.length);
        assertThat(wire[0] & 0xFF).isEqualTo(TdsErrorToken.TOKEN_TYPE);
    }

    @Test
    void tryParseRejectsTruncatedOrWrongType() {
        assertThat(TdsErrorToken.tryParse(new byte[]{0x00}, 0)).isEmpty();
        byte[] shortHeader = new byte[]{(byte) 0xAA, 0x10, 0x00};
        assertThat(TdsErrorToken.tryParse(shortHeader, 0)).isEmpty();
    }

    @Test
    void tryParseAcceptsUshortLineNumberRemainder() {
        // Manually build ERROR with USHORT LineNumber (pre-7.2 width).
        byte[] msg = "x".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        java.nio.ByteBuffer body = java.nio.ByteBuffer.allocate(4 + 1 + 1 + 2 + msg.length + 1 + 1 + 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        body.putInt(100);
        body.put((byte) 1);
        body.put((byte) 16);
        body.putShort((short) 1);
        body.put(msg);
        body.put((byte) 0); // server
        body.put((byte) 0); // proc
        body.putShort((short) 7); // line USHORT
        byte[] bodyBytes = body.array();
        java.nio.ByteBuffer wire = java.nio.ByteBuffer.allocate(3 + bodyBytes.length)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        wire.put((byte) TdsErrorToken.TOKEN_TYPE);
        wire.putShort((short) bodyBytes.length);
        wire.put(bodyBytes);

        Optional<TdsErrorToken> parsed = TdsErrorToken.tryParse(wire.array(), 0);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().lineNumber()).isEqualTo(7);
    }
}
