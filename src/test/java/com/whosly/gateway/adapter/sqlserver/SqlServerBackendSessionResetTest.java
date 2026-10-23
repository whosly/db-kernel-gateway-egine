package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.LoopbackSockets;
import com.whosly.gateway.adapter.protocol.ObservationConfidence;
import com.whosly.gateway.adapter.protocol.ProtocolConnectionState;
import com.whosly.gateway.adapter.protocol.SessionDirtiness;
import com.whosly.gateway.adapter.protocol.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SqlServerBackendSessionResetTest {

    private static final TdsFrameCodec CODEC = new TdsFrameCodec();

    @Test
    void buildSpResetConnectionPayloadContainsProcName() {
        byte[] payload = SqlServerBackendSessionReset.buildSpResetConnectionPayload();
        String asUtf16 = new String(payload, java.nio.charset.StandardCharsets.UTF_16LE);
        assertThat(asUtf16).contains("sp_reset_connection");
    }

    @Test
    void returnsTrueWhenBackendSendsDoneProc() throws Exception {
        try (LoopbackSockets.Pair pair = LoopbackSockets.open()) {
            Thread server = new Thread(() -> {
                try {
                    // Read RPC packet header + payload
                    byte[] header = LoopbackSockets.readExact(pair.serverSide().getInputStream(), 8);
                    assertThat(header[0] & 0xFF).isEqualTo(TdsPacketType.RPC.getCode());
                    int packetLen = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
                    LoopbackSockets.readExact(pair.serverSide().getInputStream(), packetLen - 8);
                    byte[] doneProc = new byte[TdsDoneToken.WIRE_LENGTH];
                    doneProc[0] = (byte) TdsTokenType.DONEPROC.getCode();
                    // status FINAL, curCmd 0, rowCount 0
                    byte[] reply = CODEC.packet(TdsPacketType.TABULAR_RESULT.getCode(), doneProc, 1);
                    pair.serverSide().getOutputStream().write(reply);
                    pair.serverSide().getOutputStream().flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "tds-reset-ok");
            server.start();
            SqlServerBackendSessionReset reset = new SqlServerBackendSessionReset();
            assertThat(reset.reset(pair.clientSide(), reusableSnapshot())).isTrue();
            server.join();
        }
    }

    @Test
    void returnsFalseWhenBackendSendsErrorThenDone() throws Exception {
        try (LoopbackSockets.Pair pair = LoopbackSockets.open()) {
            Thread server = new Thread(() -> {
                try {
                    byte[] header = LoopbackSockets.readExact(pair.serverSide().getInputStream(), 8);
                    int packetLen = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
                    LoopbackSockets.readExact(pair.serverSide().getInputStream(), packetLen - 8);
                    byte[] error = TdsErrorToken.encode(50000, 1, 16, "reset failed", "srv");
                    byte[] done = TdsDoneToken.encodeErrorFinal();
                    byte[] payload = new byte[error.length + done.length];
                    System.arraycopy(error, 0, payload, 0, error.length);
                    System.arraycopy(done, 0, payload, error.length, done.length);
                    pair.serverSide().getOutputStream().write(
                            CODEC.packet(TdsPacketType.TABULAR_RESULT.getCode(), payload, 1));
                    pair.serverSide().getOutputStream().flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "tds-reset-err");
            server.start();
            SqlServerBackendSessionReset reset = new SqlServerBackendSessionReset();
            assertThat(reset.reset(pair.clientSide(), reusableSnapshot())).isFalse();
            server.join();
        }
    }

    @Test
    void scanTokensDetectsFinalDoneProc() {
        byte[] doneProc = new byte[TdsDoneToken.WIRE_LENGTH];
        doneProc[0] = (byte) TdsTokenType.DONEPROC.getCode();
        SqlServerBackendSessionReset.TokenScan scan = SqlServerBackendSessionReset.scanTokens(doneProc);
        assertThat(scan.finalDone()).isTrue();
        assertThat(scan.error()).isFalse();
    }

    private static SessionSnapshot reusableSnapshot() {
        return new SessionSnapshot(
                "SQLServer", "s1", ProtocolConnectionState.READY, ObservationConfidence.CONFIRMED,
                false, Optional.empty(), Optional.empty(), SessionDirtiness.clean(),
                Instant.now(), Instant.now());
    }
}
