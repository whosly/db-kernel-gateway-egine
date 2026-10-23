package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.masking.MaskedValue;
import com.whosly.gateway.masking.MaskingException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TdsBinaryValuesTest {

    @Test
    void roundTripsIntn() {
        TdsColumnDefinition col = new TdsColumnDefinition(
                "n", TdsTypeCode.INTN, 4, 0, 0, 1, 4, false, null);
        byte[] wire = TdsBinaryValues.encode(col, MaskedValue.ofText("42", StandardCharsets.UTF_8));
        assertThat(wire).hasSize(4);
        assertThat(TdsBinaryValues.decodeToText(col, wire)).isEqualTo("42");
    }

    @Test
    void roundTripsDecimal() {
        TdsColumnDefinition col = new TdsColumnDefinition(
                "amount", TdsTypeCode.DECIMALN, 9, 18, 2, 1, 0, false, null);
        byte[] wire = TdsBinaryValues.encode(col, MaskedValue.ofText("12.50", StandardCharsets.UTF_8));
        assertThat(wire[0]).isEqualTo((byte) 1); // nonnegative
        assertThat(TdsBinaryValues.decodeToText(col, wire)).isEqualTo("12.50");
    }

    @Test
    void roundTripsDate() {
        TdsColumnDefinition col = new TdsColumnDefinition(
                "d", TdsTypeCode.DATEN, 3, 0, 0, 1, 3, false, null);
        byte[] wire = TdsBinaryValues.encode(col, MaskedValue.ofText("1970-01-01", StandardCharsets.UTF_8));
        assertThat(wire).hasSize(3);
        assertThat(TdsBinaryValues.decodeToText(col, wire)).isEqualTo("1970-01-01");
    }

    @Test
    void roundTripsTimeScale3() {
        TdsColumnDefinition col = new TdsColumnDefinition(
                "t", TdsTypeCode.TIMEN, 0, 0, 3, 1, 0, false, null);
        byte[] wire = TdsBinaryValues.encode(col, MaskedValue.ofText("01:02:03.125", StandardCharsets.UTF_8));
        assertThat(wire).hasSize(4);
        assertThat(TdsBinaryValues.decodeToText(col, wire)).isEqualTo("01:02:03.125");
    }

    @Test
    void roundTripsDateTime2() {
        TdsColumnDefinition col = new TdsColumnDefinition(
                "ts", TdsTypeCode.DATETIME2N, 0, 0, 0, 1, 0, false, null);
        byte[] wire = TdsBinaryValues.encode(col, MaskedValue.ofText("2020-05-06 07:08:09", StandardCharsets.UTF_8));
        assertThat(wire).hasSize(3 + 3); // time scale0 = 3 + date 3
        assertThat(TdsBinaryValues.decodeToText(col, wire)).isEqualTo("2020-05-06 07:08:09");
    }

    @Test
    void rejectsNonDecimalText() {
        TdsColumnDefinition col = new TdsColumnDefinition(
                "amount", TdsTypeCode.DECIMALN, 9, 10, 2, 1, 0, false, null);
        assertThatThrownBy(() -> TdsBinaryValues.encode(col, MaskedValue.ofText("***", StandardCharsets.UTF_8)))
                .isInstanceOf(MaskingException.class);
    }

    @Test
    void moneyTypeIsEncodable() {
        assertThat(TdsBinaryValues.isEncodable(TdsTypeCode.MONEYTYPE)).isTrue();
        assertThat(TdsBinaryValues.isEncodable(TdsTypeCode.DECIMALN)).isTrue();
    }
}
