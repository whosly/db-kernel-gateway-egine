package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.masking.MaskedValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TdsRowCodecTest {

    @Test
    void roundTripsNvarcharRow() {
        byte[] meta = TdsTestFrames.nvarcharColumnMeta("email", 100, true);
        TdsColumnDefinition col = TdsColMetadata.tryParse(meta, 0).orElseThrow().columns().get(0);
        byte[] row = TdsTestFrames.nvarcharRow("alice@example.com");
        Optional<TdsRowCodec.ParsedRow> parsed = TdsRowCodec.tryParse(row, 0, List.of(col));
        assertThat(parsed).isPresent();
        assertThat(parsed.get().values().get(0).isNull()).isFalse();
        assertThat(new String(parsed.get().values().get(0).bytes(), StandardCharsets.UTF_16LE))
                .isEqualTo("alice@example.com");

        byte[] encoded = TdsRowCodec.encode(List.of(col),
                List.of(MaskedValue.ofText("REDACTED", StandardCharsets.UTF_8)));
        Optional<TdsRowCodec.ParsedRow> again = TdsRowCodec.tryParse(encoded, 0, List.of(col));
        assertThat(again).isPresent();
        assertThat(new String(again.get().values().get(0).bytes(), StandardCharsets.UTF_16LE))
                .isEqualTo("REDACTED");
    }

    @Test
    void roundTripsGuidRow() {
        byte[] meta = TdsTestFrames.guidColumnMeta("id", true);
        TdsColumnDefinition col = TdsColMetadata.tryParse(meta, 0).orElseThrow().columns().get(0);
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        byte[] row = TdsTestFrames.guidRow(uuid);
        Optional<TdsRowCodec.ParsedRow> parsed = TdsRowCodec.tryParse(row, 0, List.of(col));
        assertThat(parsed).isPresent();
        assertThat(SqlServerResultSetMaskingInterceptor.decodeGuid(parsed.get().values().get(0).bytes()))
                .isEqualTo(uuid.toString());

        byte[] encoded = TdsRowCodec.encode(List.of(col),
                List.of(MaskedValue.ofText("00000000-0000-0000-0000-000000000000", StandardCharsets.UTF_8)));
        Optional<TdsRowCodec.ParsedRow> again = TdsRowCodec.tryParse(encoded, 0, List.of(col));
        assertThat(again).isPresent();
        assertThat(SqlServerResultSetMaskingInterceptor.decodeGuid(again.get().values().get(0).bytes()))
                .isEqualTo("00000000-0000-0000-0000-000000000000");
    }

    @Test
    void parsesNullNvarchar() {
        byte[] meta = TdsTestFrames.nvarcharColumnMeta("email", 100, true);
        TdsColumnDefinition col = TdsColMetadata.tryParse(meta, 0).orElseThrow().columns().get(0);
        byte[] row = TdsTestFrames.nvarcharNullRow();
        Optional<TdsRowCodec.ParsedRow> parsed = TdsRowCodec.tryParse(row, 0, List.of(col));
        assertThat(parsed).isPresent();
        assertThat(parsed.get().values().get(0).isNull()).isTrue();
    }
}
