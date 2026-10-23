package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.masking.MaskedValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TdsRowCodecNbcPlpTest {

    @Test
    void roundTripsNbcRowWithNullBitmap() {
        byte[] meta = TdsTestFrames.twoNvarcharColumnMeta("email", "phone", true);
        List<TdsColumnDefinition> cols = TdsColMetadata.tryParse(meta, 0).orElseThrow().columns();
        byte[] nbc = TdsTestFrames.nbcNvarcharRow("alice@example.com", null);
        Optional<TdsRowCodec.ParsedRow> parsed = TdsRowCodec.tryParseNbc(nbc, 0, cols);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().values().get(0).isNull()).isFalse();
        assertThat(new String(parsed.get().values().get(0).bytes(), StandardCharsets.UTF_16LE))
                .isEqualTo("alice@example.com");
        assertThat(parsed.get().values().get(1).isNull()).isTrue();

        byte[] encoded = TdsRowCodec.encodeNbc(cols, List.of(
                MaskedValue.ofText("REDACTED", StandardCharsets.UTF_8),
                MaskedValue.ofNull()));
        Optional<TdsRowCodec.ParsedRow> again = TdsRowCodec.tryParseNbc(encoded, 0, cols);
        assertThat(again).isPresent();
        assertThat(new String(again.get().values().get(0).bytes(), StandardCharsets.UTF_16LE))
                .isEqualTo("REDACTED");
        assertThat(again.get().values().get(1).isNull()).isTrue();
    }

    @Test
    void roundTripsNonNullPlpNvarchar() {
        byte[] meta = TdsTestFrames.nvarcharMaxColumnMeta("bio", true);
        TdsColumnDefinition col = TdsColMetadata.tryParse(meta, 0).orElseThrow().columns().get(0);
        assertThat(col.plp()).isTrue();
        assertThat(col.supportsNonNullRewrite()).isTrue();

        byte[] row = TdsTestFrames.nvarcharPlpRow("hello-plp");
        Optional<TdsRowCodec.ParsedRow> parsed = TdsRowCodec.tryParse(row, 0, List.of(col));
        assertThat(parsed).isPresent();
        assertThat(new String(parsed.get().values().get(0).bytes(), StandardCharsets.UTF_16LE))
                .isEqualTo("hello-plp");

        byte[] encoded = TdsRowCodec.encode(List.of(col),
                List.of(MaskedValue.ofText("MASKED", StandardCharsets.UTF_8)));
        Optional<TdsRowCodec.ParsedRow> again = TdsRowCodec.tryParse(encoded, 0, List.of(col));
        assertThat(again).isPresent();
        assertThat(new String(again.get().values().get(0).bytes(), StandardCharsets.UTF_16LE))
                .isEqualTo("MASKED");
    }

    @Test
    void roundTripsDecimalRow() {
        byte[] meta = TdsTestFrames.decimalColumnMeta("amount", 18, 2, true);
        TdsColumnDefinition col = TdsColMetadata.tryParse(meta, 0).orElseThrow().columns().get(0);
        assertThat(col.supportsNonNullRewrite()).isTrue();
        byte[] row = TdsTestFrames.decimalRow(18, 2, "99.99");
        Optional<TdsRowCodec.ParsedRow> parsed = TdsRowCodec.tryParse(row, 0, List.of(col));
        assertThat(parsed).isPresent();
        byte[] encoded = TdsRowCodec.encode(List.of(col),
                List.of(MaskedValue.ofText("0.00", StandardCharsets.UTF_8)));
        Optional<TdsRowCodec.ParsedRow> again = TdsRowCodec.tryParse(encoded, 0, List.of(col));
        assertThat(again).isPresent();
        assertThat(TdsBinaryValues.decodeToText(col, again.get().values().get(0).bytes()))
                .isEqualTo("0.00");
    }
}
