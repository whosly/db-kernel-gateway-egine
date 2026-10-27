package com.whosly.gateway.adapter.sqlserver;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TdsColMetadataTest {

    @Test
    void parsesNvarcharColumn() {
        byte[] meta = TdsTestFrames.nvarcharColumnMeta("email", 100, true);
        Optional<TdsColMetadata.Parsed> parsed = TdsColMetadata.tryParse(meta, 0);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().columns()).hasSize(1);
        TdsColumnDefinition col = parsed.get().columns().get(0);
        assertThat(col.name()).isEqualTo("email");
        assertThat(col.type()).isEqualTo(TdsTypeCode.NVARCHAR);
        assertThat(col.nullable()).isTrue();
        assertThat(col.supportsNonNullRewrite()).isTrue();
        assertThat(col.supportsNullRewrite()).isTrue();
        assertThat(parsed.get().wireLength()).isEqualTo(meta.length);
    }

    @Test
    void parsesGuidColumn() {
        byte[] meta = TdsTestFrames.guidColumnMeta("id", true);
        Optional<TdsColMetadata.Parsed> parsed = TdsColMetadata.tryParse(meta, 0);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().columns().get(0).type()).isEqualTo(TdsTypeCode.GUIDTYPE);
        assertThat(parsed.get().columns().get(0).supportsNonNullRewrite()).isTrue();
    }

    @Test
    void rejectsNoMetaDataCount() {
        byte[] wire = new byte[]{(byte) 0x81, (byte) 0xFF, (byte) 0xFF};
        assertThat(TdsColMetadata.tryParse(wire, 0)).isEmpty();
    }
}
