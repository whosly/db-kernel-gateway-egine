package com.whosly.gateway.adapter.sqlserver;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TdsDoneTokenTest {

    @Test
    void encodeErrorFinalRoundTripsThroughTryParse() {
        byte[] wire = TdsDoneToken.encodeErrorFinal();
        Optional<TdsDoneToken> parsed = TdsDoneToken.tryParse(wire, 0);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().isError()).isTrue();
        assertThat(parsed.get().isAttentionAck()).isFalse();
        assertThat(parsed.get().isFinal()).isTrue();
        assertThat(parsed.get().wireLength()).isEqualTo(TdsDoneToken.WIRE_LENGTH);
    }

    @Test
    void encodeAttentionAckSetsDoneAttnFlag() {
        byte[] wire = TdsDoneToken.encodeAttentionAck();
        Optional<TdsDoneToken> parsed = TdsDoneToken.tryParse(wire, 0);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().isAttentionAck()).isTrue();
        assertThat(parsed.get().status() & TdsDoneToken.STATUS_ATTN).isEqualTo(TdsDoneToken.STATUS_ATTN);
    }

    @Test
    void tryParseRejectsTruncatedOrWrongToken() {
        assertThat(TdsDoneToken.tryParse(new byte[]{(byte) 0xFD, 0x00}, 0)).isEmpty();
        assertThat(TdsDoneToken.tryParse(new byte[]{(byte) 0xAA}, 0)).isEmpty();
        assertThat(TdsDoneToken.tryParse(null, 0)).isEmpty();
    }
}
